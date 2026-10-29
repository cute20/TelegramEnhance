package io.github.telegramenhance.api;

import io.github.telegramenhance.rules.*;
import io.github.telegramenhance.rules.RuleModels.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/rules")
public final class RuleController {
    private final RuleService service;private final RuleDefaultsService defaults;
    public RuleController(RuleService service,RuleDefaultsService defaults){this.service=service;this.defaults=defaults;}
    public record NameInput(String link){@Override public String toString(){return "NameInput[local link]";}}
    @PostMapping("/name")public CompletableFuture<RuleDefaultsService.Name> name(@RequestBody NameInput input){return defaults.name(input.link());}
    @GetMapping public Listing list(){return service.list();}
    @PostMapping public View create(@RequestBody RuleService.Create request){return service.create(request);}
    @GetMapping("/by-operation/{operation}")public View lookup(@PathVariable String operation){return service.lookup(operation);}
    @GetMapping("/{id}")public View get(@PathVariable String id){return service.get(id);}
    @PutMapping("/{id}")public View update(@PathVariable String id,@RequestBody RuleService.Update request){return service.update(id,request);}
    @PostMapping("/{id}/enabled")public View enabled(@PathVariable String id,@RequestBody RuleService.Toggle request){return service.toggle(id,request);}
    @PostMapping("/{id}/observe")public CompletableFuture<View> observe(@PathVariable String id,@RequestBody RuleService.Position request){return service.observe(id,request);}
    @PostMapping("/{id}/preview")public CompletableFuture<Preview> preview(@PathVariable String id,@RequestBody RuleService.Position request){return service.preview(id,request);}
    @GetMapping("/{id}/versions")public List<Version> versions(@PathVariable String id){return service.versions(id);}
}
