package io.github.zhumaniezov.codex.edt.tests;

import org.junit.Test;
import org.junit.Assume;
import org.eclipse.ui.PlatformUI;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.semantic.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;

/** Явный native audit, выполняемый только в одноразовой workspace полной EDT. */
public class CapabilityProbeTest {
    @Test public void probeNativeFactoriesAndServices() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("codex.edt.capabilityProbe"));
        var future=java.util.concurrent.CompletableFuture.runAsync(()->{
            var results=new JsonArray();
            var project=SemanticFixture.create("CapabilityProbe"+System.nanoTime());
            try(var metadata=new EdtMetadataService(project)) {
                for(var type:new MetadataTypeRegistry().all()) {
                    var entry=object("kind",type.name());
                    try {
                        var plan=new MetadataPlan(object("operations",java.util.List.of(object("operation","create","kind",type.name(),"name","Проверка"+type.name()))));
                        entry.add("result",metadata.apply(plan));
                        entry.addProperty("nativeCreated",true);
                    }catch(Exception error){entry.addProperty("error",error.toString());error.printStackTrace();}
                    results.add(entry);
                }
                var provider=IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(URI.createURI("probe.form"));
                for(var api:java.util.List.of(com._1c.g5.v8.dt.form.generator.IFormGenerator.class,
                        com._1c.g5.v8.dt.form.generator.IFormFieldGenerator.class,
                        com._1c.g5.v8.dt.form.service.item.IFormItemManagementService.class,
                        com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService.class,
                        com._1c.g5.v8.dt.form.service.command.FormCommandManagementService.class)) {
                    var entry=object("service",api.getName());
                    try{entry.addProperty("available",provider!=null && provider.get(api)!=null);}catch(Exception error){entry.addProperty("error",error.toString());}
                    results.add(entry);
                }
                var destination=java.nio.file.Path.of(System.getProperty("codex.edt.smoke.result")).getParent().resolve("capability-probe.json");
                java.nio.file.Files.writeString(destination,new GsonBuilder().setPrettyPrinting().create().toJson(results));
                System.out.println("CAPABILITY_PROBE "+destination);
            }catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
            finally{SemanticFixture.delete(project);}
        });
        var display=PlatformUI.getWorkbench().getDisplay();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        while(!future.isDone() && System.nanoTime()<deadline){if(!display.readAndDispatch())Thread.sleep(10);}
        future.get(1,java.util.concurrent.TimeUnit.SECONDS);
    }
}
