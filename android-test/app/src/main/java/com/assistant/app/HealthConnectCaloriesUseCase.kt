package com.assistant.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectCaloriesUseCase(private val context: Context) {
    companion object {
        // В 1.0.0-alpha07 createReadPermission возвращает HealthPermission, не String.
        val PERMISSIONS: Set<HealthPermission> = setOf(
            HealthPermission.createReadPermission(ActiveCaloriesBurnedRecord::class)
        )
    }

    private val client: HealthConnectClient? by lazy {
        // 1.0.0-alpha07: isAvailable() сначала, getOrCreate() — только если установлен.
        if (HealthConnectClient.isAvailable(context)) HealthConnectClient.getOrCreate(context) else null
    }

    fun isAvailable(): Boolean = HealthConnectClient.isAvailable(context)

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions(PERMISSIONS).containsAll(PERMISSIONS)
    }

    /**
     * Возвращает активные потраченные ккал за текущий локальный день.
     * Basal/BMR калории не читаем: используем только ActiveCaloriesBurnedRecord.
     */
    suspend fun getTodayActiveCalories(): Double {
        val c = client ?: return 0.0
        if (!hasPermissions()) throw SecurityException("Нет разрешения Health Connect")

        val zone = ZoneId.systemDefault()
        val startOfDay: Instant = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now: Instant = Instant.now()
        val metric: AggregateMetric<androidx.health.connect.client.units.Energy> =
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL

        val result = c.aggregate(
            AggregateRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
            )
        )

        return result[metric]?.inKilocalories ?: 0.0
    }
}
