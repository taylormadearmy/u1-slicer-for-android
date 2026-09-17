package com.u1.slicer.ui.printer

internal enum class AddPrinterChoice {
    SNAPMAKER_U1,
    BAMBU_LAN,
    ORCA_CATALOGUE,
    ORCA_IMPORT,
}

internal fun visibleAddPrinterChoices(experimentalEnabled: Boolean): List<AddPrinterChoice> =
    if (experimentalEnabled) AddPrinterChoice.entries else listOf(AddPrinterChoice.SNAPMAKER_U1)
