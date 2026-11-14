// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;

import java.io.File;
import java.util.Collections;
import java.util.UUID;

final class ComposerContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        Composer composer = new Composer();
        composer.text("draft");
        Composer.Content previous = composer.content();
        long generation = composer.generation();
        long first = composer.nextEdit();
        composer.edit("draft", "draft!", generation, first);
        check(
                composer.content().text.equals("draft!") && previous.text.equals("draft"),
                "editing creates a new value without changing an observed composition");
        composer.quote("one\ntwo");
        check(
                composer.message(true).equals("> one\n> two\n\ndraft!")
                        && composer.message(false).isEmpty(),
                "quoted text belongs only to text-mode composition");
        composer.sent(false);
        check(
                composer.content().text.equals("draft!")
                        && composer.content().quote.equals("one\ntwo"),
                "an attachment-only send retains text and quote");
        composer.sent(true);
        long late = composer.nextEdit();
        composer.edit("draft!", "late IME text", generation, late);
        check(
                composer.content().text.isEmpty()
                        && composer.content().quote.isEmpty()
                        && composer.editAck() == late,
                "a late IME edit is acknowledged without restoring a sent draft");
        composer.edit("", "new", composer.generation(), composer.nextEdit());
        composer.append("shared");
        File file = File.createTempFile("pocket-composer-", ".txt");
        try {
            Attachment uploading =
                    new Attachment(
                            "fixture.txt",
                            "text/plain",
                            file.getPath(),
                            "",
                            "",
                            0,
                            Attachment.UPLOADING);
            ImportBatch batch =
                    new ImportBatch(
                            UUID.randomUUID().toString(),
                            "qa",
                            "import",
                            0,
                            Collections.singletonList(uploading));
            Composer.Content admitted = composer.importing(batch);
            check(
                    composer.content().attachments.isEmpty()
                            && !composer.content().text.contains("import"),
                    "preparing an import leaves state unchanged until its transaction commits");
            composer.imported(admitted);
            Composer.Content frozen = composer.content();
            check(
                    !composer.attachmentsReady()
                            && composer.retireUploads()
                            && composer.content().attachments.get(0).state == Attachment.FAILED
                            && frozen.attachments.get(0).state == Attachment.UPLOADING,
                    "credential retirement fails uploads without mutating an observed draft");
            composer.restore(frozen.text, frozen.quote, frozen.encodedAttachments());
            check(
                    composer.content().attachments.size() == 1
                            && composer.content().attachments.get(0).state == Attachment.FAILED,
                    "restoration retains owned local files and retires interrupted uploads");
            composer.remove(file.getPath());
            check(
                    !composer.replace(uploading) && composer.content().attachments.isEmpty(),
                    "a late upload cannot reinsert an attachment the user removed");
            file.delete();
            composer.restore(frozen.text, frozen.quote, frozen.encodedAttachments());
            check(
                    composer.content().attachments.isEmpty(),
                    "missing local files cannot reappear on restore");
            composer.reset();
            composer.edit("new", "old room", generation, composer.nextEdit());
            check(
                    composer.content().text.isEmpty(),
                    "room reset invalidates old editor generations");
        } finally {
            file.delete();
        }
        return checked;
    }
}
