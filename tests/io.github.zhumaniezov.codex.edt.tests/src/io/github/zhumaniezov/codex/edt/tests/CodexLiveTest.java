package io.github.zhumaniezov.codex.edt.tests;

import org.junit.Assume;
import org.junit.Test;

public class CodexLiveTest {
    @Test
    public void answersThroughRealCodexInEdt() throws Exception {
        Assume.assumeTrue("Реальный запрос включается только явно", Boolean.getBoolean("codex.edt.live"));
        ViewScenario.run(true);
    }
    @org.junit.Test
    public void dirtyBufferAndServerHistory() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("codex.edt.live"));
        DirtyLiveScenario.run();
    }
}

