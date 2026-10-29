package io.github.telegramenhance.api;

import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.downloadrules.DownloadRuleService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/rules/download")
public final class DownloadRuleController {
    private final DownloadRuleService service;
    public DownloadRuleController(DownloadRuleService service){this.service=service;}
    @GetMapping public Listing list(){return service.list();}
    @GetMapping("/{id}")public View get(@PathVariable String id){return service.get(id);}
    @GetMapping("/by-operation/{operation}")public org.springframework.http.ResponseEntity<?> lookup(@PathVariable String operation){
        var value=service.lookup(operation);
        return value==null?org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("null"):org.springframework.http.ResponseEntity.ok(value);
    }
    @PostMapping public CompletableFuture<View> create(@RequestBody DownloadRuleService.Create request){return service.create(request);}
    @PutMapping("/{id}")public CompletableFuture<View> update(@PathVariable String id,@RequestBody DownloadRuleService.Update request){return service.update(id,request);}
    @PostMapping("/{id}/enabled")public View toggle(@PathVariable String id,@RequestBody DownloadRuleService.Toggle request){return service.toggle(id,request);}
    @PostMapping("/{id}/check")public CompletableFuture<View> check(@PathVariable String id,@RequestBody DownloadRuleService.Position request){return service.check(id,request);}
    @PostMapping("/{id}/takeover")public View takeover(@PathVariable String id,@RequestBody DownloadRuleService.Takeover request){return service.takeover(id,request);}
    @GetMapping("/{id}/versions")public List<Version> versions(@PathVariable String id){return service.versions(id);}
}
