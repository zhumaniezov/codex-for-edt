package io.github.zhumaniezov.codex.edt.semantic;

import com.google.gson.JsonObject;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;

public record EdtToolDescriptor(String name,String area,boolean writes,JsonObject definition) {
    public JsonObject diagnostic() {return object("name",name,"area",area,"writes",writes,"apiStatus","PUBLIC_SUPPORTED");}
}
