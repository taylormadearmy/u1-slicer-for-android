package com.u1.slicer.machine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PrinterWizardStep { VENDOR, MODEL, VARIANT, CONFIRM }

data class PrinterWizardVariant(
    val profile: MachineProfile,
    val sourcePath: String = profile.id,
)

data class PrinterWizardState(
    val step: PrinterWizardStep = PrinterWizardStep.VENDOR,
    val vendors: List<String> = emptyList(),
    val models: List<VendorCatalogEntry> = emptyList(),
    val variants: List<PrinterWizardVariant> = emptyList(),
    val selectedVendor: String? = null,
    val selectedModel: VendorCatalogEntry? = null,
    val selectedVariant: PrinterWizardVariant? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

data class MachineProfileValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
) {
    companion object {
        fun success() = MachineProfileValidationResult(isValid = true)
        fun failure(message: String) = MachineProfileValidationResult(isValid = false, errorMessage = message)
    }
}

/** Coroutine-backed state holder for the text-only Orca printer setup wizard. */
class PrinterWizardViewModel(
    vendorNames: List<String> = VendorCatalogClient.VENDOR_NAMES,
    private val fetchIndex: suspend (String) -> VendorCatalogIndex,
    private val resolveMachine: suspend (String, String) -> String?,
    private val validate: suspend (MachineProfile) -> MachineProfileValidationResult,
    private val save: suspend (MachineProfile, String, String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        PrinterWizardState(
            // Bambu profiles remain on the dedicated Bambu LAN path in v1.
            vendors = vendorNames.filterNot { it.equals("BBL", ignoreCase = true) },
        ),
    )
    val state: StateFlow<PrinterWizardState> = _state.asStateFlow()

    suspend fun selectVendor(vendor: String) = runBusy {
        require(vendor in _state.value.vendors) { "Unknown vendor: $vendor" }
        val index = withContext(ioDispatcher) { fetchIndex(vendor) }
        _state.value = _state.value.copy(
            step = PrinterWizardStep.MODEL,
            selectedVendor = vendor,
            selectedModel = null,
            selectedVariant = null,
            models = index.machineModelList.distinctBy { it.name },
            variants = emptyList(),
        )
        currentIndex = index
    }

    suspend fun selectModel(model: VendorCatalogEntry) = runBusy {
        val vendor = requireNotNull(_state.value.selectedVendor)
        val index = requireNotNull(currentIndex)
        require(model in index.machineModelList) { "Unknown model: ${model.name}" }
        val profileNames = model.machineProfileNames()
        val candidates = index.machineList.filter { entry ->
            profileNames.any { profileName ->
                entry.name == profileName || entry.name.startsWith("$profileName ")
            }
        }
        val variants = withContext(ioDispatcher) {
            candidates.mapNotNull { entry ->
                val resolved = resolveMachine(vendor, entry.name) ?: return@mapNotNull null
                val vendorQualified = JSONObject(resolved).apply {
                    if (optString("vendor").isBlank()) put("vendor", vendor)
                }.toString()
                val profile = MachineProfile.fromResolvedOrcaJson(vendorQualified, MachineFamily.GENERIC)
                    ?: return@mapNotNull null
                profile.takeIf { it.printerModel in profileNames && isWizardSupported(it) }
                    ?.let { PrinterWizardVariant(profile = it, sourcePath = entry.subPath) }
            }
        }
        _state.value = _state.value.copy(
            step = PrinterWizardStep.VARIANT,
            selectedModel = model,
            selectedVariant = null,
            variants = variants,
            errorMessage = if (variants.isEmpty()) "No supported variants are available for ${model.name}." else null,
        )
    }

    fun selectVariant(variant: PrinterWizardVariant) {
        require(variant in _state.value.variants) { "Unknown printer variant" }
        _state.value = _state.value.copy(
            step = PrinterWizardStep.CONFIRM,
            selectedVariant = variant,
            errorMessage = null,
        )
    }

    suspend fun confirm(nickname: String, moonrakerUrl: String) = runBusy {
        val profile = requireNotNull(_state.value.selectedVariant).profile
        val result = withContext(ioDispatcher) { validate(profile) }
        if (!result.isValid) {
            _state.value = _state.value.copy(
                errorMessage = result.errorMessage ?: "Machine profile validation failed.",
            )
            return@runBusy
        }
        withContext(ioDispatcher) {
            save(profile, nickname.trim().ifBlank { profile.displayName }, moonrakerUrl.trim())
        }
        _state.value = _state.value.copy(saved = true, errorMessage = null)
    }

    fun back() {
        _state.value = when (_state.value.step) {
            PrinterWizardStep.VENDOR -> _state.value
            PrinterWizardStep.MODEL -> _state.value.copy(
                step = PrinterWizardStep.VENDOR,
                selectedVendor = null,
                models = emptyList(),
            )
            PrinterWizardStep.VARIANT -> _state.value.copy(
                step = PrinterWizardStep.MODEL,
                selectedModel = null,
                variants = emptyList(),
            )
            PrinterWizardStep.CONFIRM -> _state.value.copy(
                step = PrinterWizardStep.VARIANT,
                selectedVariant = null,
            )
        }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, errorMessage = null)
        try {
            block()
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                errorMessage = error.message ?: "Printer setup failed.",
            )
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    private var currentIndex: VendorCatalogIndex? = null

    private fun VendorCatalogEntry.machineProfileNames(): Set<String> = buildSet {
        add(name)
        subPath.substringAfterLast('/')
            .removeSuffix(".json")
            .takeIf(String::isNotBlank)
            ?.let(::add)
    }

    companion object {
        /** Delta beds and machines with more than four physical extruders are outside the v1 boundary. */
        fun isWizardSupported(profile: MachineProfile): Boolean {
            val json = runCatching { JSONObject(profile.machineJson) }.getOrNull() ?: return false
            if (json.optString("printer_structure").equals("delta", ignoreCase = true)) return false
            return profile.extruderCount in 1..4
        }
    }
}
