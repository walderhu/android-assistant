package com.assistant.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NutritionViewModel(app: Application) : AndroidViewModel(app) {
    sealed class ActiveCaloriesState {
        object Idle : ActiveCaloriesState()
        object Loading : ActiveCaloriesState()
        object PermissionRequired : ActiveCaloriesState()
        object HealthConnectUnavailable : ActiveCaloriesState()
        data class Value(val kcal: Double) : ActiveCaloriesState()
        data class Error(val message: String) : ActiveCaloriesState()
    }

    private val useCase = HealthConnectCaloriesUseCase(app.applicationContext)
    private val _activeCalories = MutableStateFlow<ActiveCaloriesState>(ActiveCaloriesState.Idle)
    val activeCalories: StateFlow<ActiveCaloriesState> = _activeCalories

    fun loadTodayActiveCalories() {
        viewModelScope.launch {
            _activeCalories.value = ActiveCaloriesState.Loading
            try {
                if (!useCase.isAvailable()) {
                    _activeCalories.value = ActiveCaloriesState.HealthConnectUnavailable
                    return@launch
                }
                if (!useCase.hasPermissions()) {
                    _activeCalories.value = ActiveCaloriesState.PermissionRequired
                    return@launch
                }
                _activeCalories.value = ActiveCaloriesState.Value(useCase.getTodayActiveCalories())
            } catch (e: SecurityException) {
                _activeCalories.value = ActiveCaloriesState.PermissionRequired
            } catch (e: Exception) {
                _activeCalories.value = ActiveCaloriesState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
