package com.crewpocket.mate.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InterfaceLanguageTest {

    @Test
    public void supportsChineseAndEnglish() {
        assertEquals(InterfaceLanguage.ZH, InterfaceLanguage.valueOf("ZH"));
        assertEquals(InterfaceLanguage.EN, InterfaceLanguage.valueOf("EN"));
    }
}
