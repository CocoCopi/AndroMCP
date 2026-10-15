package com.cococopi.andromcp.mcp.oauth

import com.cococopi.andromcp.data.repository.OAuthClientRepository
import com.cococopi.andromcp.geo.GeoIpResolver

/**
 * Groups the OAuth collaborators passed into [com.cococopi.andromcp.mcp.McpServer]
 * so its constructor stays within detekt's `LongParameterList` threshold (no `@Suppress` needed).
 */
class OAuthServerDeps(
    val jwtTokenService: JwtTokenService,
    val oauthClientRepository: OAuthClientRepository,
    val authorizationCodeStore: AuthorizationCodeStore,
    val approvalCoordinator: OAuthApprovalCoordinator,
    val geoIpResolver: GeoIpResolver,
)
