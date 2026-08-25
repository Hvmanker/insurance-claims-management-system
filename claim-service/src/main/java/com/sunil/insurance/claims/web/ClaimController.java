package com.sunil.insurance.claims.web;

import com.sunil.insurance.claims.service.ClaimService;
import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.service.PortalUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/claims")
public class ClaimController {
    private final ClaimService claimService;
    private final PortalUserService portalUserService;

    public ClaimController(ClaimService claimService, PortalUserService portalUserService) {
        this.claimService = claimService;
        this.portalUserService = portalUserService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse submit(@Valid @RequestBody SubmitClaimRequest request, java.security.Principal principal) {
        return claimService.submit(request, portalUserService.currentUser(principal));
    }

    @GetMapping
    public List<ClaimResponse> recentClaims(java.security.Principal principal) {
        return claimService.recentClaims(portalUserService.currentUser(principal));
    }

    @GetMapping("/summary")
    public ClaimService.ClaimDashboardSummary summary(java.security.Principal principal) {
        return claimService.dashboardSummary(portalUserService.currentUser(principal));
    }

    @GetMapping("/{claimId}")
    public ClaimResponse get(@PathVariable("claimId") UUID claimId, java.security.Principal principal) {
        return claimService.get(claimId, portalUserService.currentUser(principal));
    }

    @PostMapping("/{claimId}/decision")
    public ClaimResponse decide(@PathVariable("claimId") UUID claimId, @Valid @RequestBody ManualDecisionRequest request, java.security.Principal principal) {
        return claimService.decide(claimId, request, portalUserService.currentUser(principal));
    }
}
