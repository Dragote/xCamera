package com.dragote.xcamera.shared.common.domain.result

/**
 * Standard success/error wrapper used across every repository/use-case boundary,
 * so ViewModels never have to deal with thrown exceptions from the data layer.
 */
sealed interface Result<out D, out E : DataError> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : DataError>(val error: E) : Result<Nothing, E>
}
