package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "auth_accounts",
    indices = [Index(value = ["username"], unique = true)]
)
data class AuthAccountEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val passwordSalt: String,
    val passwordHash: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class SellerAccountSummary(
    val username: String,
    val displayName: String,
    val createdAt: Long
)
