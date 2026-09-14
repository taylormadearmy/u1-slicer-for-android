package com.u1.slicer.ui.printer

internal enum class AddPrinterChoice {
    SNAPMAKER_U1,
    BAMBU_LAN,
    ORCA_CATALOGUE,
    ORCA_IMPORT,
}

internal sealed interface AddPrinterNextStep {
    data object Proceed : AddPrinterNextStep
    data object RequireAcknowledgement : AddPrinterNextStep
}

internal fun addPrinterNextStep(
    choice: AddPrinterChoice,
    acknowledged: Boolean,
): AddPrinterNextStep = if (choice == AddPrinterChoice.SNAPMAKER_U1 || acknowledged) {
    AddPrinterNextStep.Proceed
} else {
    AddPrinterNextStep.RequireAcknowledgement
}
