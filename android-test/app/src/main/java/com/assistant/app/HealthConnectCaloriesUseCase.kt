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
     * Возвращает активные потраченные ккал за указанный локальный день.
     * Для сегодня end = now; для прошлых — конец дня.
     * Basal/BMR калории не читаем: используем только ActiveCaloriesBurnedRecord.
     */
    suspend fun getActiveCaloriesForDay(date: LocalDate): Double {
        val c = client ?: return 0.0
        if (!hasPermissions()) return 0.0

        val zone = ZoneId.systemDefault()
        val startOfDay: Instant = date.atStartOfDay(zone).toInstant()
        val today = LocalDate.now(zone)
        val endInstant: Instant = if (date == today) Instant.now()
            else date.plusDays(1).atStartOfDay(zone).toInstant()
        val metric: AggregateMetric<androidx.health.connect.client.units.Energy> =
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL

        val result = c.aggregate(
            AggregateRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, endInstant)
            )
        )

        return result[metric]?.inKilocalories ?: 0.0
    }

    /** Активные ккал за сегодня. */
    suspend fun getTodayActiveCalories(): Double =
        getActiveCaloriesForDay(LocalDate.now())
}
