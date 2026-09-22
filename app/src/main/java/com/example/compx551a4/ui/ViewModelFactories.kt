package com.example.compx551a4.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.compx551a4.AppContainer
import com.example.compx551a4.FocusApp

// [D] The one line of DI plumbing every ViewModel factory needs.
//
// Each ViewModel declares `companion object { val Factory = viewModelFactory { initializer {
// XxxViewModel(container()) } } }`, which is enough structure for five screens and leaves the
// ViewModels themselves constructible with plain arguments in tests.

fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FocusApp).container
