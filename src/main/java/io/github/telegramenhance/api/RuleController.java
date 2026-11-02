package io.github.telegramenhance.api;

import io.github.telegramenhance.rules.*;
import io.github.telegramenhance.rules.RuleModels.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController @RequestMapping("/api/rules")
public final class RuleController {
    private final RuleService service;private final RuleDefaultsService defaults;private final RulePreparationService preparations;
    public RuleController(RuleService service,RuleDefaultsService defaults){this(service,defaults,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public RuleController(RuleService service,RuleDefaultsService defaults,RulePreparationService preparations){this.service=service;this.defaults=defaults;this.preparations=preparations;}
    public record NameInput(String link){@Override public String toString(){return "NameInput[local link]";}}
    @PostMapping("/name")public CompletableFuture<RuleDefaultsService.Name> name(@RequestBody NameInput input){return defaults.name(input.link());}
    @GetMapping public Listing list(){return service.list();}
    @PostMapping public View create(@RequestBody RuleService.Create request){return service.create(request);}
    @GetMapping("/by-operation/{operation}")public View lookup(@PathVariable String operation){return service.lookup(operation);}
    @GetMapping("/{id}")public View get(@PathVariable String id){return service.get(id);}
    @PutMapping("/{id}")public View update(@PathVariable String id,@RequestBody RuleService.Update request){return service.update(id,request);}
    @PostMapping("/{id}/enabled")public View enabled(@PathVariable String id,@RequestBody RuleService.Toggle request){return service.toggle(id,request);}
    @PostMapping("/{id}/observe")public DeferredResult<View> observe(@PathVariable String id,@RequestBody RuleService.Position request){
        return bounded(service.observe(id,request),75_000,"目录检测结果尚未确认，请刷新规则核对；不会自动重复检测或启用上传");
    }
    @PostMapping("/{id}/preview")public DeferredResult<Preview> preview(@PathVariable String id,@RequestBody RuleService.Position request){
        return bounded(service.preview(id,request),135_000,"分组预览结果尚未确认，请刷新规则核对；不会自动重试、启用或发送文件");
    }
    @GetMapping("/{id}/versions")public List<Version> versions(@PathVariable String id){return service.versions(id);}
    @PostMapping("/{id}/preparations")public RulePreparationService.View prepare(@PathVariable String id,@RequestBody RulePreparationService.Request request){return preparations.start(id,request);}
    @GetMapping("/{id}/preparations/by-operation/{operation}")public ResponseEntity<?> preparation(@PathVariable String id,@PathVariable String operation){var result=preparations.lookup(id,operation);return result==null?ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("null"):ResponseEntity.ok(result);}
    private static <T> DeferredResult<T> bounded(CompletableFuture<T> future,long timeout,String message){
        var result=new DeferredResult<T>(timeout);
        // Keep the underlying read/detection alive: an HTTP timeout does not undo its eventual observation.
        result.onTimeout(()->result.setErrorResult(ResponseEntity.status(503).body(Map.of("message",message))));
        future.whenComplete((value,error)->{if(error!=null)result.setErrorResult(error);else result.setResult(value);});
        return result;
    }
}
