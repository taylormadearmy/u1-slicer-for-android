package com.u1.slicer

/** Tests whose fixture setup requires the named host driver, outside the test process. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresExternalFixture(val driver: String)
