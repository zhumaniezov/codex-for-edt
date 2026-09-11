package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.eclipse.swt.graphics.RGB;
import io.github.zhumaniezov.codex.edt.ui.presentation.*;
import io.github.zhumaniezov.codex.edt.ui.render.*;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.context.EditorContext;
import io.github.zhumaniezov.codex.edt.settings.*;

public class UiPresentationTest {
    @Test public void composerEnablesOnlyAppropriateActions() {
        assertFalse(new ComposerState(true,false,false,false).canSend());
        assertTrue(new ComposerState(true,false,false,true).canSend());
        assertFalse(new ComposerState(false,false,false,true).canSend());
        assertFalse(new ComposerState(true,true,false,true).canSend());
        var working=new ComposerState(true,true,true,true);
        assertTrue(working.canStop());assertFalse(working.canSend());assertFalse(working.canConfigure());
    }
    @Test public void lightPaletteSeparatesSurfacesAndKeepsReadableText(){palette(new RGB(240,240,240),new RGB(30,30,30));}
    @Test public void darkPaletteSeparatesSurfacesAndKeepsReadableText(){palette(new RGB(35,38,41),new RGB(230,230,230));}
    @Test public void pureWhiteThemeStillSeparatesComposer(){palette(new RGB(255,255,255),new RGB(20,20,20));}
    private void palette(RGB background,RGB foreground){
        var value=PaletteModel.from(background,foreground,new RGB(255,255,255),new RGB(0,0,0));
        assertNotEquals(value.panel(),value.input());assertNotEquals(value.input(),value.footer());assertNotEquals(value.footer(),value.border());
        assertTrue(PaletteModel.contrast(value.input(),value.text())>=4.5);
        assertTrue(PaletteModel.contrast(value.panel(),value.muted())>=4.5);
        assertTrue(PaletteModel.contrast(value.accent(),value.onAccent())>=4.5);
    }
    @Test public void inheritedUnreadableForegroundIsCorrected(){
        var value=PaletteModel.from(new RGB(40,40,40),new RGB(45,45,45),new RGB(255,255,255),new RGB(0,0,0));
        assertTrue(PaletteModel.contrast(value.input(),value.text())>=4.5);
    }
    private Path directory()throws Exception{return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"codex-attachments-");}
    @Test public void attachmentReferencesAreDeduplicatedAndRemoved()throws Exception{
        var root=directory();var file=Files.writeString(root.resolve("Module.bsl"),"saved");var selection=new AttachmentSelection();
        var item=AttachmentSelection.validate(root,file);selection.add(item);selection.add(item);
        assertEquals(List.of("Module.bsl"),selection.references(root));assertEquals("saved",Files.readString(file));
        selection.remove(item);assertTrue(selection.items().isEmpty());
    }
    @Test public void attachmentsCannotEscapeProjectAndAreRevalidatedBeforeSend()throws Exception{
        var root=directory();var other=directory();var file=Files.writeString(other.resolve("private.txt"),"private");
        assertThrows(java.io.IOException.class,()->AttachmentSelection.validate(root,file));
        assertThrows(java.io.IOException.class,()->AttachmentSelection.validate(root,root.resolve("..").resolve(other.getFileName()).resolve("private.txt")));
        var local=Files.writeString(root.resolve("local.txt"),"local");var selection=new AttachmentSelection();selection.add(AttachmentSelection.validate(root,local));
        assertThrows(java.io.IOException.class,()->selection.references(other));Files.delete(local);
        assertThrows(java.io.IOException.class,()->selection.references(root));
    }
    @Test public void attachmentLimitAndNewChatClearAreExplicit()throws Exception{
        var root=directory();var selection=new AttachmentSelection();
        for(int i=0;i<5;i++){selection.add(AttachmentSelection.validate(root,Files.writeString(root.resolve("file"+i),"content")));}
        var sixth=AttachmentSelection.validate(root,Files.writeString(root.resolve("sixth"),"content"));
        assertThrows(IllegalStateException.class,()->selection.add(sixth));assertEquals(5,selection.items().size());
        selection.clear();assertTrue(selection.references(root).isEmpty());
    }
    @Test public void attachmentsUseQuotedPathsOnlyAndDoNotChangeSandbox()throws Exception{
        var context=new EditorContext("Project","","","C:/example");
        var request=new ChatRequest("Question",context,List.of("src/Module.bsl"));var prompt=ReadOnlyPolicy.prompt(request);
        assertTrue(prompt.contains("\"src/Module.bsl\""));assertEquals(List.of(),new ChatRequest("Question",context).attachments());
        var turn=ReadOnlyPolicy.turn("thread","C:/example","discovered-model",prompt);
        assertEquals("never",turn.get("approvalPolicy").getAsString());assertEquals("readOnly",turn.getAsJsonObject("sandboxPolicy").get("type").getAsString());
        assertEquals("text",turn.getAsJsonArray("input").get(0).getAsJsonObject().get("type").getAsString());
    }
    @Test public void approvalFoundationCannotGrantPermissions(){
        for(var model:List.of(ApprovalPresenter.policy(),new ApprovalPresenter("Request"))){assertFalse(model.canApprove());assertFalse(model.canAutoApprove());assertFalse(model.canReject());}
        assertFalse(ApprovalPresenter.policy().hasRequest());assertTrue(new ApprovalPresenter("Request").hasRequest());
    }
    @Test public void transcriptRoleBoundariesComeFromMessagesAndNotMarkdown(){
        var doc=ConversationDocument.parse(List.of(new SessionData.Message("Пользователь","Question"),new SessionData.Message("Codex","**Пользователь**\n\nGenerated text\n```bsl\nСообщить(\"Привет\");\n```")));
        assertEquals(2,doc.blocks().size());assertTrue(doc.blocks().get(0).user());assertFalse(doc.blocks().get(1).user());
        assertTrue(doc.spans().stream().anyMatch(span->span.style().code()));assertTrue(doc.text().contains("Generated text"));
    }
    @Test public void relativeTimesAndNewLabelsAreLocalized()throws Exception{
        var store=EdtPreferencesService.store();String previous=store.getString(EdtPreferencesService.LANGUAGE);
        try{store.setValue(EdtPreferencesService.LANGUAGE,"ru");assertEquals("5м",ChatListPresentation.time(700,1000));assertEquals("2ч",ChatListPresentation.time(0,7200));
            store.setValue(EdtPreferencesService.LANGUAGE,"en");assertEquals("5m",ChatListPresentation.time(700,1000));assertEquals("now",ChatListPresentation.time(1100,1000));
            for(String key:List.of("composerPlaceholder","approvalChip","approvalHint","attach","attachmentRemove","conversation")){assertNotEquals(LocalizationService.text(key,Locale.ENGLISH),LocalizationService.text(key,Locale.forLanguageTag("ru")));}
        }finally{store.setValue(EdtPreferencesService.LANGUAGE,previous);store.save();}
    }
}
