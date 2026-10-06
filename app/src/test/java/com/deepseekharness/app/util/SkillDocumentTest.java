package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SkillDocumentTest {
  private static SkillDocument parse(String source) throws IOException {
    return SkillDocument.parse(source.getBytes(StandardCharsets.UTF_8));
  }

  private static void rejects(String code, String source) throws IOException {
    try {
      parse(source);
      fail("expected " + code);
    } catch (IOException error) {
      assertEquals(code, error.getMessage());
    }
  }

  @Test
  public void parsesMultilineDescriptionWithoutChangingSource() throws Exception {
    String source =
        "---\r\nname: hello-world\r\ndescription: |\r\n  工作说明\r\n  Second line\r\n---\r\n\r\n# Instructions\r\nRun no command automatically.\r\n";
    SkillDocument document = parse(source);
    assertEquals("hello-world", document.name);
    assertEquals("工作说明\nSecond line\n", document.description);
    assertEquals(source, document.text);
    assertTrue(document.modelInvocable);
    assertTrue(document.userInvocable);
  }

  @Test
  public void usesCore12StringsAndOfficialInvocationFields() throws Exception {
    SkillDocument document =
        parse(
            "---\nname: on\ndescription: no\ndisable-model-invocation: yes\nuser-invocable: 0\n---\nInstructions");
    assertEquals("on", document.name);
    assertEquals("no", document.description);
    assertFalse(document.modelInvocable);
    assertFalse(document.userInvocable);
    assertTrue(
        parse("---\nname: yes\ndescription: okay\nuser-invocable: 1.0\n---\n").userInvocable);
  }

  @Test
  public void rejectsNamesThatCouldEscapeTheSkillFolder() throws Exception {
    for (String name :
        new String[] {"../outside", "a/b", "a\\b", "UPPER", "two words", "-bad", "a--b"})
      rejects("SKILL_NAME", "---\nname: '" + name + "'\ndescription: okay\n---\nbody");
  }

  @Test
  public void requiresTheActualFrontmatterAndStrings() throws Exception {
    rejects("SKILL_FRONTMATTER", "# skill\nname: okay\ndescription: okay");
    rejects("SKILL_FRONTMATTER", "---\nname: okay\ndescription: okay\n");
    rejects("SKILL_REQUIRED_FIELDS", "---\nname: okay\ndescription: 12\n---\nbody");
    rejects("SKILL_REQUIRED_FIELDS", "---\nname: okay\ndescription: ''\n---\nbody");
    rejects("SKILL_REQUIRED_FIELDS", "---\nname: 123\ndescription: okay\n---\nbody");
  }

  @Test
  public void rejectsInvalidOrLegacyInvocationControls() throws Exception {
    rejects(
        "SKILL_INVOCATION", "---\nname: okay\ndescription: okay\nmodelInvocable: true\n---\nbody");
    rejects(
        "SKILL_INVOCATION",
        "---\nname: okay\ndescription: okay\nuser-invocable: sometimes\n---\nbody");
    rejects(
        "SKILL_INVOCATION",
        "---\nname: okay\ndescription: okay\ndisable-model-invocation: null\n---\nbody");
  }

  @Test
  public void doesNotAcceptAliasesTagsOrDuplicateKeys() throws Exception {
    rejects("SKILL_FRONTMATTER", "---\nname: okay\nname: different\ndescription: okay\n---\nbody");
    rejects(
        "SKILL_FRONTMATTER",
        "---\nname: okay\ndescription: &desc okay\nmetadata: {copy: *desc}\n---\nbody");
    rejects(
        "SKILL_FRONTMATTER", "---\nname: okay\ndescription: !!java.lang.String okay\n---\nbody");
    rejects(
        "SKILL_FRONTMATTER",
        "---\nname: okay\ndescription: okay\nmetadata: {bad: !!int abc}\n---\nbody");
  }

  @Test
  public void boundsActualBytesAndEncodingAndFilename() throws Exception {
    try {
      SkillDocument.parse(new byte[SkillDocument.MAX_BYTES + 1]);
      fail();
    } catch (IOException error) {
      assertEquals("SKILL_SIZE", error.getMessage());
    }
    try {
      SkillDocument.parse(new byte[] {(byte) 0xc3, (byte) 0x28});
      fail();
    } catch (IOException error) {
      assertEquals("SKILL_ENCODING", error.getMessage());
    }
    SkillDocument.filename("SKILL.md");
    for (String name :
        new String[] {"SKILL.md.zip", "skill.md", "../SKILL.md", "folder/SKILL.md", null})
      try {
        SkillDocument.filename(name);
        fail();
      } catch (IOException error) {
        assertEquals("SKILL_FILENAME", error.getMessage());
      }
  }
}
