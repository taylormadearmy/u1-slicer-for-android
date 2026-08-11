package com.u1.slicer.ui.printer

/** Show the quick selector only when there is a real choice to make. */
internal fun shouldShowActivePrinterSelector(printerCount: Int): Boolean = printerCount > 1
