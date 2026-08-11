package com.u1.slicer.printer

sealed class TransportCommandResult {
    data object Success : TransportCommandResult()
    data class Unsupported(val reason: String) : TransportCommandResult()
    data class Failure(val reason: String) : TransportCommandResult()
}
