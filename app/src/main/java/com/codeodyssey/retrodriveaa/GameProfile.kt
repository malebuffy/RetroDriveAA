package com.codeodyssey.retrodriveaa

data class GameProfile(
    val gameId: String,
    val configOverrides: Map<String, String> = emptyMap()
)
