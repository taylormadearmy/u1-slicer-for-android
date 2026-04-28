review 1:

Architectural Review: Phase 2 Canonical Slicer (v1.6.13 to HEAD df1817e)
An analysis of the Phase 1 and Phase 2 migrations reveals a conceptually sound goal—aligning the mobile slicer's data model with industry-standard canonical file-relative filaments—but exposes severe structural vulnerabilities in its execution. The implementation relies on brittle heuristics, temporal state mutation, and leaky abstractions that actively threaten the stability of the core slicing engine.

Below is an adversarial examination of the underlying assumptions driving the current architecture.

1. The "Quarantined Canonical" Assumption and Data Flow Integrity
The Assumption: Phase 2 reframes the entire pipeline to emit canonical G-code (T<fileIndex>), assuming that the applyPrintTimeRemap function safely translates all outputs to physical G-code (T<physicalSlot>) at the export boundaries (Send, Save, Share).

The Socratic Challenge: Does the architecture structurally guarantee that canonical G-code can never reach the physical hardware, or does it rely on developers remembering to call a helper function?

The Reality: The abstraction leaks. While the primary UI paths (Send Dialog, Preview Save/Share) correctly invoke applyPrintTimeRemap, the system lacks a type-safe boundary distinguishing between "Canonical G-code" and "Physical G-code".

The 4th Path Vulnerability: The JobsScreen -> shareJobGcode(job) path completely bypasses the remap. It directly shares raw canonical slicer output.
Consequence: A user printing this shared file will inject unmapped canonical tool indices (e.g., T4 through T11) into firmware that only understands physical slots T0-T3. This will result in deterministic hardware faults, skipped tool changes, or unpredictable material extrusion. The architecture relies on "hope" rather than type safety.
2. The Semantic Fragility of is_snapmaker_profile
The Assumption: The system can accurately detect a Snapmaker-authored 3MF file to safely gate the application of embedded configuration profiles, ensuring Bambu/Prusa limits don't corrupt U1 prints.

The Socratic Challenge: Is searching for the substring "PRINT_START" in the machine start G-code a robust, mathematically sound proxy for "This is a native Snapmaker profile"?

The Reality: This heuristic is a ticking timebomb.

PRINT_START is the ubiquitous standard macro for Klipper firmware.
If a user imports a generic 3MF file originally prepared in Bambu Studio for any Klipper-based printer (Voron, RatRig, etc.), the substring match will yield a false positive.
Consequence: The slicer will erroneously apply the entire profile_keys[] whitelist. Because Bambu profiles utilize proprietary template variables (e.g., flush_volumetric_speeds), the minimal embedded U1 Print pipeline will fail to parse them, resulting in fatal SIGSEGV crashes during slice execution. Furthermore, this bypasses the hardware safeguards, injecting foreign kinematics and limits into the U1.
3. Hidden Coupling and the Illusion of Configuration Modularity
The Assumption: The system successfully achieves a clean configuration pipeline by pulling profile_keys[] from the native embed and mixing them with user overrides via JNI in applyConfigToPrusa.

The Socratic Challenge: If the system is genuinely modular and profile_keys[] explicitly whitelists parameters from the 3MF, why are those parameters immediately overwritten? Is the pipeline actually structural, or merely order-dependent?

The Reality: The configuration architecture is highly coupled and relies on chronological stomping rather than hierarchical merging.

Dead Keys as Architectural Debt: The profile_keys[] array extracts parameters like fan_min_speed, hot_plate_temp, and retraction_minimum_travel. However, applyConfigToPrusa runs afterwards and unconditionally overwrites them with hardcoded values. This renders the C++ whitelist deceptive and unmaintainable.
C++ Hardware Coupling: The goal of a "native-first read pipeline" is undermined by the fact that U1 physical hardware limits (Arachne wall generator defaults, max volumetric speeds, deretraction speeds) are hardcoded directly inside sapil_print.cpp. The slicing engine is not a modular component receiving a machine profile from Kotlin; it is permanently and rigidly coupled to the U1 hardware via C++ inline strings and floats.
4. State Space Expansion vs. Padding Abstractions (SEMM Amplification)
The Assumption: Expanding the physical slot array to a 10-wide canonical list accurately models the filament map without affecting the underlying C++ toolpath logic.

The Socratic Challenge: How does a legacy C++ engine, designed for dense arrays, react when handed a sparse 10-wide array where only 4 elements are populated?

The Reality: The B96 SEMM T-index amplification is not an isolated bug; it is a direct consequence of the architectural phase transition.

Legacy padding logic (like B48) naturally extends the last known valid index across unused array slots to prevent null pointer exceptions.
By inflating the canonical space to 10, but frequently only utilizing a subset (e.g., 4 colors), the legacy C++ padding interprets the empty slots as redundant tool states. This tricks the toolpath generator into emitting hundreds of phantom transitions, heavily inflating wipe-tower waste. The data structure was expanded, but the consumption logic's iteration bounds were not audited to match.
Verdict & Strategic Pivots
The transition to a canonical filament model is the correct trajectory for the project's long-term scalability. However, the current C++ boundary implementation introduces unacceptable risk. Fundamental pivots are required before merging:

Introduce G-code Type Safety: Kotlin must enforce a strict distinction between CanonicalGcode and PhysicalGcode (e.g., via value classes). The shareJobGcode function and all export pipelines must be forced by the compiler to only accept PhysicalGcode, structurally eliminating the 4th-path leak.
Deprecate Regex Heuristics: The find("PRINT_START") logic must be immediately replaced. Profile provenance should be determined by explicit JSON metadata flags (e.g., generator tags) or structured capabilities, never by parsing G-code macros.
Invert the Config Pipeline: Resolve the JNI coupling by moving the U1 hardware profile out of sapil_print.cpp. Kotlin should construct a fully resolved configuration map (Base U1 Profile + 3MF Canonical Parameters + User Overrides) and push it through JNI as a single source of truth, rather than relying on C++ to juggle overrides and hardcodes in a temporal sequence.



review 2:

Findings
High — GcodeParser still ignores T10+ commands, breaking Phase 2 multi-filament parsing assumptions.
Phase 2 explicitly allows canonical tool indices beyond 4 (and the brief cites fixtures reaching 11), but parser tool-change logic only handles two-character Tn commands. Any T10, T11, etc. is skipped, so subsequent moves stay attributed to the previous extruder. That can silently corrupt preview coloring, per-extruder usage, and any downstream logic relying on parsed tool assignment.

GcodeParser.kt
Lines 260-267

// T0–T9 — tool change. Phase 2 — no longer clamped to 0..3
// because multi-filament files (paint segmentation / MMU)
// legitimately use higher T-indices.
if (c0 == 'T' && cmdLen == 2 && l[start + 1] in '0'..'9') {
    val raw = l[start + 1] - '0'
    currentExtruder = raw.coerceIn(0, 31)  // safety cap
    ensureExtruderCapacity(currentExtruder)
}
High — Send/upload path can bypass print-time remap and push canonical-space G-code directly.
In the canonical == null fallback branch, upload/print is executed with the original pending.gcodePath instead of forcing remap. For a canonical-space sliced file (T<fileIndex>), this risks sending unsupported tool indices to firmware (or wrong physical-slot mapping), exactly the class of failure Phase 2 is trying to eliminate.

MainActivity.kt
Lines 731-738
// Fallback: no canonical list available (no model
// loaded, unrecognised file). Send unchanged.
when (pending.action) {
    PendingMappingSend.Action.PrintAndUpload ->
        printerViewModel.sendAndPrint(pending.gcodePath)
    PendingMappingSend.Action.UploadOnly ->
        printerViewModel.sendUploadOnly(pending.gcodePath)
}
Medium — Snapmaker-profile detection is brittle (contains("PRINT_START")) and can misclassify profiles.
The native gate decides whether to apply the broad profile_keys[] whitelist from a substring search in machine_start_gcode. That’s permissive and can false-positive on non-Snapmaker content (comments/macros/templated text containing PRINT_START). Misclassification here has large blast radius because it changes which embedded keys are trusted.

sapil_print.cpp
Lines 550-567
bool is_snapmaker_profile = false;
if (!model_config.empty()) {
    auto* start_opt = model_config.option<Slic3r::ConfigOptionString>("machine_start_gcode");
    if (start_opt && start_opt->value.find("PRINT_START") != std::string::npos) {
        is_snapmaker_profile = true;
    }
}
if (!model_config.empty()) {
    if (is_snapmaker_profile) {
        // Keys safe to apply from the embedded Snapmaker profile.
Open Questions / Assumptions
I’m assuming canonical-space T indices are still present in the sliced source file before remap (as documented in your brief). If any path pre-remaps earlier, finding #2 severity drops for that specific path.
I did not run builds/tests per your request, so this is static-logic review only.
Change Summary
Reviewed against the architectural brief and focused on high-risk reliability boundaries: parser correctness for expanded tool index space, remap enforcement on all printer-bound exports, and native profile-gating robustness.
Main concerns are correctness leaks at boundary conditions (multi-digit tools, remap fallback escape hatch, profile misclassification).