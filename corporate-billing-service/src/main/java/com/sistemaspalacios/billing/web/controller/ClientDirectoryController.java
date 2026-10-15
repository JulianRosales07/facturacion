package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.ClientDirectoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/clientes")
@PreAuthorize("hasAuthority('CLIENTS_VIEW')")
public class ClientDirectoryController {
    private final ClientDirectoryService service;
    public ClientDirectoryController(ClientDirectoryService service) { this.service = service; }
    @GetMapping
    public List<ClientDirectoryService.ClientEntry> list() { return service.list(); }
}
