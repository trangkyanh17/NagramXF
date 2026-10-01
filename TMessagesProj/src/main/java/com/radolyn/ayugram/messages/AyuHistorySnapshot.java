package com.radolyn.ayugram.messages;

import com.radolyn.ayugram.database.entities.EditedMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AyuHistorySnapshot {
    private final List<EditedMessage> revisions;
    private final String[] attachmentFileNames;

    private AyuHistorySnapshot(List<EditedMessage> revisions, String[] attachmentFileNames) {
        List<EditedMessage> safeRevisions = revisions == null ? Collections.emptyList() : revisions;
        this.revisions = Collections.unmodifiableList(new ArrayList<>(safeRevisions));
        this.attachmentFileNames = attachmentFileNames == null ? null : attachmentFileNames.clone();
    }

    public static AyuHistorySnapshot of(List<EditedMessage> revisions, String[] attachmentFileNames) {
        return new AyuHistorySnapshot(revisions, attachmentFileNames);
    }

    public static AyuHistorySnapshot empty() {
        return new AyuHistorySnapshot(Collections.emptyList(), null);
    }

    public List<EditedMessage> getRevisions() {
        return revisions;
    }

    public String[] getAttachmentFileNames() {
        return attachmentFileNames == null ? null : attachmentFileNames.clone();
    }
}
