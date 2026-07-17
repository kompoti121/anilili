package com.miruronative.data.auth

/** Which list service the user is signed into. The app allows exactly one at a time. */
enum class AccountService(val label: String) {
    ANILIST("AniList"),
    MAL("MyAnimeList"),
    ;

    companion object {
        /** AniList wins if both somehow hold tokens (pre-MAL installs can only have AniList). */
        val active: AccountService?
            get() = when {
                AuthManager.isLoggedIn -> ANILIST
                MalAuthManager.isLoggedIn -> MAL
                else -> null
            }
    }
}
