package com.localcaption.instant;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/** All UI state lives on the main thread; no audio or transcript is persisted. */
public final class CaptionState {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final CaptionState S = new CaptionState();
    public interface Observer { void changed(); }
    public static final class Line {
        public final long id;
        public String en = "", zh = "", problem = "";
        public boolean done;
        public int revision;
        public long recognitionSequence;
        Line(long id) { this.id = id; }
    }
    public final ArrayList<Line> lines = new ArrayList<>();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    public volatile boolean running, ready, preparing;
    public volatile String status = "正在准备离线引擎…";
    public String source = "麦克风";
    public int level, translateMs, asrMs, skippedSegments;
    public boolean overlay;
    private long nextId;
    private long recognitionSequence;
    long recognitionSequence(){return recognitionSequence;}
    public synchronized long nextId() { return ++nextId; }
    public void observe(Observer o) { observers.add(o); }
    public void unobserve(Observer o) { observers.remove(o); }
    public void changed() { for (Observer o : observers) o.changed(); }
    public void status(String s) { MAIN.post(() -> { status = s; changed(); }); }
    public void english(long id, String text, boolean done, int revision) {
        english(id,text,done,revision,()->true);
    }
    public void english(long id,String text,boolean done,int revision,java.util.function.BooleanSupplier active) {
        MAIN.post(() -> { if(!active.getAsBoolean())return;Line line = find(id); if(!text.equals(line.en))line.recognitionSequence=++recognitionSequence;line.en = text; line.done = done; line.revision = revision; changed(); });
    }
    public void chinese(long id, String text, int revision, int ms) {
        MAIN.post(() -> {
            Line line = findExisting(id);
            if (line == null || line.revision != revision) return;
            line.zh = text; line.problem=""; translateMs = ms; changed();
        });
    }
    public void translationProblem(long id,int revision,String reason){MAIN.post(()->{Line line=findExisting(id);if(line!=null&&line.revision==revision){line.problem=reason;skippedSegments++;changed();}});}
    private Line find(long id) {
        Line found = findExisting(id);
        if (found != null) return found;
        Line line = new Line(id); lines.add(line);
        if (lines.size() > 80) lines.remove(0);
        return line;
    }
    private Line findExisting(long id) { for (Line l : lines) if (l.id == id) return l; return null; }
    public void clear() { lines.clear(); changed(); }
    public String transcript() {
        StringBuilder s = new StringBuilder();
        for (Line l : lines) s.append(l.zh.isEmpty()?l.problem:l.zh).append('\n').append(l.en).append('\n').append('\n');
        return s.toString();
    }
}
