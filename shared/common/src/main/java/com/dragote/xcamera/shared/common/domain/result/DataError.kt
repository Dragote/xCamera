package com.dragote.xcamera.shared.common.domain.result

sealed interface DataError {

    enum class Local : DataError {
        UNKNOWN,
    }
}
