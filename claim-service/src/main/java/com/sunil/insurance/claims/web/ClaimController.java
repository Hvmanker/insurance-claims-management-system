package com.sunil.insurance.claims.web;

import com.sunil.insurance.claims.service.ClaimService;
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

@RestController
@RequestMapping("/claims")
public class ClaimController {
    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse submit(@Valid @RequestBody SubmitClaimRequest request) {
        return claimService.submit(request);
    }

    @GetMapping("/{claimId}")
    public ClaimResponse get(@PathVariable("claimId") UUID claimId) {
        return claimService.get(claimId);
    }
}
