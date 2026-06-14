@{
    Name = 'u1-slicer-confidence-check'
    InstrumentedClasses = @(
        'com.u1.slicer.native.NativeLibrarySymbolTest'
        'com.u1.slicer.native.NativeLibraryCorrectnessTest'
        'com.u1.slicer.native.NativePlateDataTest'
        'com.u1.slicer.native.NativeObjectExtruderMapTest'
        'com.u1.slicer.PreparePreviewViewModelTest'
        'com.u1.slicer.slicing.SetModelInstancesOffsetTest'
        'com.u1.slicer.SessionResumeIntegrationTest'
        'com.u1.slicer.B131B132B133DiagnosticTest'
        'com.u1.slicer.slicing.MixSlotObjectAssignBlendGateTest'
        'com.u1.slicer.slicing.SlicingIntegrationTest'
        'com.u1.slicer.slicing.BambuPipelineIntegrationTest'
        'com.u1.slicer.slicing.SemmSlicingTest'
        'com.u1.slicer.MatchAColourE2ETest'
    )
    E2EStartAt = 1
    E2EEndAt = 7
    Notes = @(
        'Baseline confidence sweep: JVM unit suite + curated instrumented smoke set + Smoke-7 manual E2E.'
        'Add `B131B132B133DiagnosticTest` or `PreparePreviewViewModelTest` when touching copy-count, bed-fit, or Prepare/slice state.'
        'Add `SessionResumeIntegrationTest` when touching session restore, DataStore persistence, or resume banners.'
        'Add `NativeObjectExtruderMapTest` when touching object/extruder routing or any object-count / map derivation path.'
        'Add `MixSlotObjectAssignBlendGateTest` when touching whole-model mix-slot assignment, setModelFilament, or blend-gated routing.'
        'Add `MatchAColourE2ETest` / mix-specific tests when touching ColorMix or filament-mapping behaviour.'
    )
}
