package com.example.compx551a4.signal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

//The two focus states that the rest of the app can react to.
enum class FocusState {
    FOCUSED,
    NOT_FOCUSED
}

//Decides whether the user is currently focused.
//Other parts of the app can observe the state, but should not change it directly.
class FocusStateMachine {

    //tart in NOT_FOCUSED until the signal processing detects focus.
    private val _focusState = MutableStateFlow(FocusState.NOT_FOCUSED)

    //Expose the current focus state so other parts of the app can observe it.
    val focusState: StateFlow<FocusState> = _focusState.asStateFlow()
}