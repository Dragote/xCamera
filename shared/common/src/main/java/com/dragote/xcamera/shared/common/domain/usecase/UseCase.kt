package com.dragote.xcamera.shared.common.domain.usecase

/**
 * Marker contract for domain use cases: one public entry point, invoked like a function.
 */
fun interface UseCase<in P, out R> {
    suspend operator fun invoke(params: P): R
}
