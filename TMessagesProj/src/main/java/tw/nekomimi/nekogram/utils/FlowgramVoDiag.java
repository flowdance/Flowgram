package tw.nekomimi.nekogram.utils;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.messenger.utils.EphemeralMessagesHelper;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import xyz.nextalone.nagram.NaConfig;

/**
 * Flowgram fork: temporary view-once (闪照) diagnostics, v2.
 *
 * Tracks messages BY IDENTITY (account + id space + dialog + message id), not
 * by their current media shape: once a valid ttl media is seen it stays
 * tracked through empty media, read events, deletions and UI updates, so the
 * "last valid state → first invalid state" transition is never filtered out
 * by noise reduction.
 *
 * Every log line carries: in-process sequence, thread id, account, and the
 * dialog (marked "unresolved" when the event carries none). Registration and
 * key events also record the two keep-switch values. Ephemeral messages are
 * registered under both the original ephemeral id and the packed UI id, and
 * conversion points log the mapping.
 *
 * Operation phases are explicit: ARRIVE (update ingress), BEFORE/AFTER
 * (around a mutation of an in-memory or persisted object), WRITE-STEP (the
 * data bound into a database statement), COMMITTED (after transaction
 * commit), SKIP / MISS / FAILED (branch outcomes).
 *
 * File state is resolved through the real per-message rules
 * (FileLoader.getPathToMessage) and reported as directory category + anonymous
 * file id + byte size for both the plain and .enc variants. exists/size is
 * observed state only — it does NOT imply a complete download.
 *
 * All work (string building, file checks, tracking, iterations) is gated on
 * enabled(), which mirrors FileLog's own switch; with logging off this class
 * does nothing. Logs carry identifiers and shape flags only — never message
 * text, media content, full paths or credentials. Remove this class and its
 * call sites once the consumption chain is identified.
 */
public final class FlowgramVoDiag {

    public static final String TAG = "[FlowgramVODiag]";
    private static final int MAX_TRACKED = 64;
    private static final AtomicLong SEQ = new AtomicLong();

    private static final Map<String, Tracked> TRACKED = Collections.synchronizedMap(
            new LinkedHashMap<String, Tracked>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    public static final class Tracked {
        public final int account;
        public final int channelId;          // 0 = non-channel id space
        public final long dialogId;
        public final int mid;                // server mid, or the ORIGINAL ephemeral id
        public final String registeredMedia;
        public final File file;              // resolved at registration; may be null
        public final File encFile;           // file + ".enc"; may be null

        Tracked(int account, int channelId, long dialogId, int mid, String media, File file, File encFile) {
            this.account = account;
            this.channelId = channelId;
            this.dialogId = dialogId;
            this.mid = mid;
            this.registeredMedia = media;
            this.file = file;
            this.encFile = encFile;
        }
    }

    private FlowgramVoDiag() {
    }

    /** Mirrors FileLog.d's own gate — no diagnostic work when logging is off. */
    public static boolean enabled() {
        return BuildVars.LOGS_ENABLED;
    }

    private static String key(int account, int channelId, long dialogId, int mid) {
        return account + ":" + channelId + ":" + dialogId + ":" + mid;
    }

    private static int channelIdOf(TLRPC.Peer peer) {
        return peer != null ? (int) peer.channel_id : 0;
    }

    /** A message qualifies for registration while it still carries a valid
     *  (not yet consumed) ttl media description. */
    private static boolean validTtlMedia(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return false;
        }
        TLRPC.MessageMedia m = message.media;
        if (m.ttl_seconds == 0 && message.ttl == 0) {
            return false;
        }
        return validMediaShape(m);
    }

    private static boolean validTtlEphemeral(TL_ephemeral.EphemeralMessage message) {
        if (message == null || message.media == null) {
            return false;
        }
        if (message.media.ttl_seconds == 0) {
            return false;
        }
        return validMediaShape(message.media);
    }

    private static boolean validMediaShape(TLRPC.MessageMedia m) {
        boolean validPhoto = m instanceof TLRPC.TL_messageMediaPhoto && m.photo != null && !(m.photo instanceof TLRPC.TL_photoEmpty);
        boolean validDocument = m instanceof TLRPC.TL_messageMediaDocument && m.document != null && !(m.document instanceof TLRPC.TL_documentEmpty);
        return validPhoto || validDocument;
    }

    /**
     * Register the identity when a valid ttl media is seen; returns true when
     * this identity is tracked (registered now or previously seen valid).
     */
    public static boolean observe(int account, long dialogId, TLRPC.Message message) {
        if (!enabled() || message == null) {
            return false;
        }
        int channelId = channelIdOf(message.peer_id);
        if (validTtlMedia(message)) {
            File[] files = resolveFiles(account, message);
            register(account, channelId, dialogId, message.id, media(message), files != null ? files[0] : null, files != null ? files[1] : null);
            return true;
        }
        return isTracked(account, channelId, dialogId, message.id);
    }

    /** Ephemeral variant: registers both the original id and the packed UI id. */
    public static boolean observeEphemeral(int account, long dialogId, TL_ephemeral.EphemeralMessage message) {
        if (!enabled() || message == null) {
            return false;
        }
        int channelId = channelIdOf(message.peer_id);
        int packed = org.telegram.messenger.MessageObject.ephemeralMessageIdPack(message.id);
        if (validTtlEphemeral(message)) {
            File[] files = resolveFiles(account, EphemeralMessagesHelper.convertEphemeralToFakeDefault(message));
            register(account, channelId, dialogId, message.id, media(message), files != null ? files[0] : null, files != null ? files[1] : null);
            register(account, channelId, dialogId, packed, "packedOf=" + message.id, files != null ? files[0] : null, files != null ? files[1] : null);
            return true;
        }
        return isTracked(account, channelId, dialogId, message.id) || isTracked(account, channelId, dialogId, packed);
    }

    private static void register(int account, int channelId, long dialogId, int mid, String mediaTag, File file, File encFile) {
        String k = key(account, channelId, dialogId, mid);
        if (TRACKED.containsKey(k)) {
            return;
        }
        TRACKED.put(k, new Tracked(account, channelId, dialogId, mid, mediaTag, file, encFile));
        log(account, "TRACK-REGISTER", dialogId, mid,
                "space=" + (channelId == 0 ? "nonChannel" : "channel:" + channelId)
                        + " keepVo=" + NaConfig.INSTANCE.getKeepViewOnceMedia().Bool()
                        + " keepDel=" + NaConfig.INSTANCE.getKeepDeletedMessages().Bool()
                        + " " + mediaTag + " " + fileTag(file, encFile));
    }

    private static String fileTag(File file, File encFile) {
        return "dir=" + (file != null && file.getParentFile() != null ? file.getParentFile().getName() : "?")
                + " id=" + (file != null ? file.getName() : "?")
                + " bytes=" + (file != null && file.exists() ? file.length() : -1)
                + " encBytes=" + (encFile != null && encFile.exists() ? encFile.length() : -1);
    }

    /** Re-check the file saved at registration (plain and .enc). Observed
     *  state only — a non-negative size does not imply a complete download. */
    public static String trackedFileState(int account, long dialogId, int mid) {
        if (!enabled()) {
            return "";
        }
        synchronized (TRACKED) {
            for (Tracked t : TRACKED.values()) {
                if (t.account == account && t.dialogId == dialogId && t.mid == mid) {
                    return "regFile[" + fileTag(t.file, t.encFile) + "]";
                }
            }
        }
        return "regFile=untracked";
    }

    /** Observation point at the ACTUAL (async) file deletion, in
     *  FileLoader.deleteFiles on the file loader queue. Matches the file
     *  against registered locators; logs observed existence before and
     *  after the delete attempt — never claims disappearance on initiation. */
    public static void noteFileDeleteExecuted(File file, boolean existsBefore, boolean existsAfter) {
        if (!enabled() || file == null) {
            return;
        }
        synchronized (TRACKED) {
            for (Tracked t : TRACKED.values()) {
                if (t.file != null && t.file.getAbsolutePath().equals(file.getAbsolutePath())) {
                    log(t.account, "FILE-DELETE-EXECUTED", t.dialogId, t.mid,
                            "id=" + file.getName() + " existsBefore=" + existsBefore + " existsAfter=" + existsAfter
                                    + " encExists=" + (t.encFile != null && t.encFile.exists()));
                    return;
                }
            }
        }
    }

    /** Identity lookup. dialogId == 0 with channelId == 0 means an event that
     *  carries no dialog (non-channel id space) — matched against any
     *  non-channel entry of that account. */
    public static boolean isTracked(int account, int channelId, long dialogId, int mid) {
        if (!enabled() || mid == 0) {
            return false;
        }
        if (dialogId != 0 || channelId != 0) {
            return TRACKED.containsKey(key(account, channelId, dialogId, mid));
        }
        synchronized (TRACKED) {
            for (Tracked t : TRACKED.values()) {
                if (t.account == account && t.channelId == 0 && t.mid == mid) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lookup when the channel id is unknown (events keyed only by dialog):
     *  matches any tracked entry with the same account + dialog + mid. */
    public static boolean isTrackedAnySpace(int account, long dialogId, int mid) {
        if (!enabled() || mid == 0) {
            return false;
        }
        synchronized (TRACKED) {
            for (Tracked t : TRACKED.values()) {
                if (t.account == account && t.dialogId == dialogId && t.mid == mid) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String media(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return "media=null";
        }
        TLRPC.MessageMedia m = message.media;
        return m.getClass().getSimpleName()
                + " ttl_s=" + m.ttl_seconds
                + " msgTtl=" + message.ttl
                + " photoEmpty=" + (m.photo instanceof TLRPC.TL_photoEmpty)
                + " photoNull=" + (m.photo == null)
                + " docEmpty=" + (m.document instanceof TLRPC.TL_documentEmpty)
                + " docNull=" + (m.document == null);
    }

    public static String media(TL_ephemeral.EphemeralMessage message) {
        if (message == null || message.media == null) {
            return "media=null";
        }
        TLRPC.MessageMedia m = message.media;
        return m.getClass().getSimpleName()
                + " ttl_s=" + m.ttl_seconds
                + " photoEmpty=" + (m.photo instanceof TLRPC.TL_photoEmpty)
                + " photoNull=" + (m.photo == null)
                + " docEmpty=" + (m.document instanceof TLRPC.TL_documentEmpty)
                + " docNull=" + (m.document == null);
    }

    public static String messageObject(org.telegram.messenger.MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return "mo=null";
        }
        return media(mo.messageOwner) + " type=" + mo.type + " contentType=" + mo.contentType;
    }

    /** Real per-message file resolution (FileLoader.getPathToMessage), which
     *  also honors this fork's ttl/keep directory rules. Safe on the storage
     *  queue: the path computation is pure; useFileDatabaseQueue is disabled
     *  so nothing waits on another queue. Returns the resolved plain file
     *  and its ".enc" counterpart for registration. */
    private static File[] resolveFiles(int account, TLRPC.Message message) {
        try {
            File file = FileLoader.getInstance(account).getPathToMessage(message, false);
            if (file == null || file.getPath().isEmpty()) {
                return null;
            }
            return new File[]{file, new File(file.getParentFile(), file.getName() + ".enc")};
        } catch (Throwable t) {
            return null;
        }
    }

    public static String fileState(int account, TLRPC.Message message) {
        if (!enabled() || message == null) {
            return "";
        }
        File[] files = resolveFiles(account, message);
        if (files == null) {
            return "file=none";
        }
        return fileTag(files[0], files[1]);
    }

    public static void log(int account, String event, long dialogId, int mid, String detail) {
        long seq = SEQ.incrementAndGet();
        String dialogPart = dialogId == 0 ? "dialog=unresolved" : ("dialog=" + dialogId);
        FileLog.d(TAG + " seq=" + seq + " tid=" + Thread.currentThread().getId() + " acc=" + account
                + " " + event + " " + dialogPart + " mid=" + mid
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }
}
