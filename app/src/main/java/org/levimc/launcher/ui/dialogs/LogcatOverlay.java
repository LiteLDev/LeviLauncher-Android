package org.levimc.launcher.ui.dialogs;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.ContextCompat;

import org.levimc.launcher.R;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class LogcatOverlay extends FrameLayout {

    private View overlayContainer;
    private RecyclerView recyclerView;
    private LogAdapter logAdapter;
    private LinearLayoutManager layoutManager;
    private EditText filterInput;
    private EditText excludeInput;
    private TextView levelFilterView;
    private ListPopupWindow levelPopup;
    private View filterBar;
    private ImageButton pauseButton, clearButton, autoScrollButton, closeButton, minimizeBubble;
    private ImageButton filterButton, filterClearButton;
    private View overlayHeader, bottomResizeBar, cornerBottomLeft, cornerBottomRight;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private final ArrayDeque<String> logBuffer = new ArrayDeque<>(2000);
    private static final int MAX_BUFFER_LINES = 2000;
    private static final int HISTORY_TRIM_INTERVAL_LINES = 250;
    private static final int MAX_BATCH_LINES = 200;
    private static final String HISTORY_FILE_NAME = "logcat_overlay_history.txt";
    private static final String HISTORY_LOCK_FILE_NAME = "logcat_overlay_history.lock";
    private final Object historyLock = new Object();
    private File historyFile;
    private File historyLockFile;
    private int pendingHistoryTrimLines = 0;

    private final ArrayBlockingQueue<String> pendingLines = new ArrayBlockingQueue<>(8192);
    private ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean renderLoopStarted = false;

    private boolean autoScroll = true, autoScrollLocked = false, paused = false, minimized = false;
    private String filterTextLower = "";
    private List<String> excludeKeywordsLower = Collections.emptyList();
    private char levelFilterChar = 0;

    private Process logcatProc;
    private Thread readerThread;

    public LogcatOverlay(Context context) { super(context); prefs = init(context); }
    public LogcatOverlay(Context context, AttributeSet attrs) { super(context, attrs); prefs = init(context); }
    public LogcatOverlay(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); prefs = init(context); }

    private SharedPreferences init(Context ctx) {
        LayoutInflater.from(ctx).inflate(R.layout.view_logcat_overlay, this, true);
        setClickable(false);
        setFocusable(false);

        overlayContainer = findViewById(R.id.overlay_container);
        recyclerView = findViewById(R.id.log_scroll);
        filterInput = findViewById(R.id.filter_input);
        excludeInput = findViewById(R.id.exclude_input);
        levelFilterView = findViewById(R.id.level_filter);
        filterBar = findViewById(R.id.filter_bar);
        clearButton = findViewById(R.id.btn_clear);
        pauseButton = findViewById(R.id.btn_pause);
        autoScrollButton = findViewById(R.id.btn_autoscroll);
        closeButton = findViewById(R.id.btn_close);
        minimizeBubble = findViewById(R.id.minimize_bubble);
        filterButton = findViewById(R.id.btn_filter);
        filterClearButton = findViewById(R.id.btn_filter_clear);
        overlayHeader = findViewById(R.id.overlay_header);
        bottomResizeBar = findViewById(R.id.bottom_resize_bar);
        cornerBottomLeft = findViewById(R.id.corner_bottom_left);
        cornerBottomRight = findViewById(R.id.corner_bottom_right);

        layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        logAdapter = new LogAdapter();
        recyclerView.setAdapter(logAdapter);
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setVerticalScrollBarEnabled(false);
        recyclerView.setHorizontalScrollBarEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE222222);
        bg.setCornerRadius(dp(12));
        overlayContainer.setBackground(bg);

        SharedPreferences sp = ctx.getSharedPreferences("LogcatOverlaySPrefs", MODE_PRIVATE);
        initHistoryFiles(ctx);
        setupUiListeners();
        restoreState(sp);
        restorePersistedHistory();
        return sp;
    }

    private void setupUiListeners() {
        clearButton.setOnClickListener(v -> {
            synchronized (logBuffer) { logBuffer.clear(); }
            pendingLines.clear();
            clearPersistedHistory();
            if (logAdapter != null) logAdapter.clear();
        });

        pauseButton.setOnClickListener(v -> {
            paused = !paused;
            pauseButton.setImageResource(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);
        });

        autoScrollButton.setOnClickListener(v -> {
            autoScrollLocked = !autoScrollLocked;
            boolean enabled = !autoScrollLocked;
            autoScroll = enabled;
            autoScrollButton.setAlpha(enabled ? 1f : 0.5f);
        });

        String[] levels = {getContext().getString(R.string.logcat_filter_all), "V", "D", "I", "W", "E", "F"};
        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(getContext(), R.layout.spinner_dropdown_item_dark, levels);
        levelFilterView.setText(levels[0]);
        levelFilterView.setOnClickListener(v -> {
            if (levelPopup == null) {
                levelPopup = new ListPopupWindow(getContext());
                levelPopup.setAdapter(levelAdapter);
                levelPopup.setAnchorView(levelFilterView);
                levelPopup.setModal(true);
                levelPopup.setBackgroundDrawable(ContextCompat.getDrawable(getContext(), R.drawable.bg_spinner_popup_dark));
                levelPopup.setOnItemClickListener((parent, itemView, pos, id) -> {
                    levelFilterChar = pos == 0 ? 0 : levels[pos].charAt(0);
                    levelFilterView.setText(levels[pos]);
                    levelPopup.dismiss();
                    scheduleFilterRefresh();
                });
            } else {
                levelPopup.setAdapter(levelAdapter);
                levelPopup.setAnchorView(levelFilterView);
            }
            int gap = dpToPx(4);
            levelPopup.setHorizontalOffset(levelFilterView.getWidth() + gap);
            levelPopup.setVerticalOffset(0);
            levelPopup.setWidth(Math.max(levelFilterView.getWidth(), dpToPx(96)));
            levelPopup.show();
        });

        filterInput.addTextChangedListener(SimpleTextWatcher.after(s -> {
            filterTextLower = s.toString().toLowerCase(Locale.ROOT).trim();
            scheduleFilterRefresh();
        }));

        excludeInput.addTextChangedListener(SimpleTextWatcher.after(s -> {
            excludeKeywordsLower = parseKeywords(s.toString().toLowerCase(Locale.ROOT));
            scheduleFilterRefresh();
        }));

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!autoScrollLocked) autoScroll = isAtBottom();
            }
        });

        if (filterButton != null) {
            filterButton.setOnClickListener(v -> {
                boolean show = filterBar.getVisibility() != VISIBLE;
                filterBar.setVisibility(show ? VISIBLE : GONE);
                // No special handling needed for popup; it computes offsets when shown
                saveState();
            });
        }

        if (filterClearButton != null) {
            filterClearButton.setOnClickListener(v -> {
                levelFilterChar = 0;
                if (levelFilterView != null) levelFilterView.setText(R.string.logcat_filter_all);
                filterInput.setText("");
                excludeInput.setText("");
                scheduleFilterRefresh();
            });
        }

        closeButton.setOnClickListener(v -> setMinimized(true));

        if (overlayHeader != null) {
            final float[] down = new float[4];
            overlayHeader.setOnTouchListener((v, e) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        down[2] = lp.leftMargin;
                        down[3] = lp.topMargin;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - down[0]);
                        int dy = Math.round(e.getRawY() - down[1]);
                        lp.leftMargin = clamp(lp.leftMargin + dx, 0, getWidth() - overlayContainer.getWidth());
                        lp.topMargin = clamp(lp.topMargin + dy, 0, getHeight() - overlayContainer.getHeight());
                        overlayContainer.setLayoutParams(lp);
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        saveState();
                        return true;
                }
                return false;
            });
            overlayHeader.setOnLongClickListener(v -> false);
        }

        if (bottomResizeBar != null) {
            final float[] down = new float[2];
            final int[] base = new int[1];
            bottomResizeBar.setOnTouchListener((v, e) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[1] = e.getRawY();
                        base[0] = overlayContainer.getHeight();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dy = Math.round(e.getRawY() - down[1]);
                        int minH = dpToPx(120);
                        lp.height = clamp(base[0] + dy, minH, getHeight());
                        overlayContainer.setLayoutParams(lp);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        saveState();
                        return true;
                }
                return false;
            });
        }

        if (cornerBottomRight != null) {
            final float[] down = new float[2];
            final int[] base = new int[2];
            cornerBottomRight.setOnTouchListener((v, e) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        base[0] = overlayContainer.getWidth();
                        base[1] = overlayContainer.getHeight();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - down[0]);
                        int dy = Math.round(e.getRawY() - down[1]);
                        int minW = dpToPx(200);
                        int minH = dpToPx(120);
                        lp.width = clamp(base[0] + dx, minW, getWidth());
                        lp.height = clamp(base[1] + dy, minH, getHeight());
                        overlayContainer.setLayoutParams(lp);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        saveState();
                        return true;
                }
                return false;
            });
        }

        if (cornerBottomLeft != null) {
            final float[] down = new float[2];
            final int[] base = new int[3];
            cornerBottomLeft.setOnTouchListener((v, e) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        base[0] = overlayContainer.getWidth();
                        base[1] = overlayContainer.getHeight();
                        base[2] = lp.leftMargin;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - down[0]);
                        int dy = Math.round(e.getRawY() - down[1]);
                        int minW = dpToPx(200);
                        int minH = dpToPx(120);
                        int newW = clamp(base[0] - dx, minW, getWidth());
                        int deltaW = newW - base[0];
                        lp.width = newW;
                        lp.leftMargin = clamp(base[2] - deltaW, 0, getWidth() - newW);
                        lp.height = clamp(base[1] + dy, minH, getHeight());
                        overlayContainer.setLayoutParams(lp);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        saveState();
                        return true;
                }
                return false;
            });
        }

        if (minimizeBubble != null) {
            minimizeBubble.setOnClickListener(v -> setMinimized(false));
            final float[] down = new float[2];
            final int[] base = new int[2];
            final boolean[] dragged = new boolean[1];
            final int[] touchSlop = new int[1];
            minimizeBubble.setOnTouchListener((v, e) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) minimizeBubble.getLayoutParams();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lp.gravity = Gravity.TOP | Gravity.START;
                        minimizeBubble.setLayoutParams(lp);
                        down[0] = e.getRawX();
                        down[1] = e.getRawY();
                        base[0] = lp.leftMargin;
                        base[1] = lp.topMargin;
                        dragged[0] = false;
                        touchSlop[0] = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - down[0]);
                        int dy = Math.round(e.getRawY() - down[1]);
                        if (!dragged[0] && (Math.abs(dx) > touchSlop[0] || Math.abs(dy) > touchSlop[0])) {
                            dragged[0] = true;
                        }
                        lp.leftMargin = clamp(base[0] + dx, 0, getWidth() - minimizeBubble.getWidth());
                        lp.topMargin = clamp(base[1] + dy, 0, getHeight() - minimizeBubble.getHeight());
                        minimizeBubble.setLayoutParams(lp);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragged[0]) {
                            saveState();
                            return true;
                        } else {
                            v.performClick();
                            return true;
                        }
                }
                return false;
            });
        }
    }

    public void start() {
        if (readerThread != null && readerThread.isAlive()) return;
        paused = false;
        if (ioExecutor.isShutdown() || ioExecutor.isTerminated()) {
            ioExecutor = Executors.newSingleThreadExecutor();
        }
        if (filterExecutor.isShutdown() || filterExecutor.isTerminated()) {
            filterExecutor = Executors.newSingleThreadExecutor();
        }
        startBackgroundRenderLoop();
        startReader();
    }

    public void stop() {
        paused = true;
        stopReader();
        // Clear any pending lines so nothing gets appended after disable
        pendingLines.clear();
        ioExecutor.shutdownNow();
        filterExecutor.shutdownNow();
        renderLoopStarted = false;
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("logcat");
                cmd.add("-v"); cmd.add("threadtime");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cmd.add("--pid");
                    cmd.add(String.valueOf(android.os.Process.myPid()));
                }
                logcatProc = new ProcessBuilder(cmd).redirectErrorStream(true).start();

                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(logcatProc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!paused) pendingLines.offer(line);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "LogcatReader");
        readerThread.start();
    }

    private void stopReader() {
        try {
            if (logcatProc != null) logcatProc.destroy();
            if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
        } catch (Throwable ignored) {}
        logcatProc = null;
        readerThread = null;
    }

    private void startBackgroundRenderLoop() {
        if (renderLoopStarted) return;
        renderLoopStarted = true;
        ioExecutor.submit(() -> {
            StringBuilder sb = new StringBuilder();
            List<String> batch = new ArrayList<>(MAX_BATCH_LINES);
            while (true) {
                try {
                    String ln = pendingLines.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (ln != null) {
                        batch.clear();
                        batch.add(ln);
                        pendingLines.drainTo(batch, MAX_BATCH_LINES - 1);

                        synchronized (logBuffer) {
                            for (String line : batch) {
                                if (logBuffer.size() >= MAX_BUFFER_LINES) logBuffer.pollFirst();
                                logBuffer.addLast(line);
                            }
                        }
                        appendPersistedHistory(batch);
                        for (String line : batch) {
                            if (passesFilter(line)) {
                                sb.append(line).append("\n");
                            }
                        }
                    }

                    if (sb.length() > 0) {
                        final String chunk = sb.toString();
                        sb.setLength(0);
                        mainHandler.post(() -> appendChunk(chunk));
                    }
                } catch (InterruptedException e) {
                    renderLoopStarted = false;
                    return;
                }
            }
        });
    }

    private void appendChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        String[] lines = chunk.split("\n");
        List<CharSequence> items = new ArrayList<>();
        for (String ln : lines) {
            if (ln.isEmpty()) continue;
            items.add(colorizeLine(ln));
        }
        int startPos = logAdapter.getItemCount();
        logAdapter.addAll(items);
        if (autoScroll) smoothScrollToBottom();
    }

    private void scheduleFilterRefresh() {
        if (filterExecutor.isShutdown() || filterExecutor.isTerminated()) {
            filterExecutor = Executors.newSingleThreadExecutor();
        }
        filterExecutor.submit(() -> {
            List<String> snapshot;
            synchronized (logBuffer) { snapshot = new ArrayList<>(logBuffer); }
            List<CharSequence> items = new ArrayList<>();
            for (String ln : snapshot) {
                if (passesFilter(ln)) {
                    items.add(colorizeLine(ln));
                }
            }
            mainHandler.post(() -> {
                boolean wasAtBottom = isAtBottom();
                int firstPos = layoutManager.findFirstVisibleItemPosition();
                View firstView = layoutManager.findViewByPosition(firstPos);
                int offset = (firstView == null) ? 0 : firstView.getTop();

                logAdapter.setItems(items);

                recyclerView.post(() -> {
                    if (wasAtBottom && autoScroll) {
                        smoothScrollToBottom();
                    } else if (firstPos >= 0) {
                        layoutManager.scrollToPositionWithOffset(Math.min(firstPos, Math.max(0, logAdapter.getItemCount()-1)), offset);
                    }
                });
            });
        });
    }

    private boolean passesFilter(@NonNull String line) {
        if (levelFilterChar != 0) {
            char c = detectLevelChar(line);
            if (c == 0 || c != levelFilterChar) return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (!filterTextLower.isEmpty() && !lower.contains(filterTextLower)) return false;
        for (String ex : excludeKeywordsLower) {
            if (!ex.isEmpty() && lower.contains(ex)) return false;
        }
        return true;
    }

    // Strict patterns to avoid matching level from message content
    private static final Pattern LOG_BRIEF = Pattern.compile("^\\s*([VDIWEF])\\s*/"); // E/Tag: ...
    private static final Pattern LOG_BRACKET = Pattern.compile("^\\s*\\[[^\\]]+\\]\\s+([VDIWEF])\\b"); // [Tag] I ...

    private char detectLevelChar(@NonNull String ln) {
        // 1) threadtime format: MM-DD HH:MM:SS pid tid L tag: msg
        Matcher mt = LOG_THREADTIME.matcher(ln);
        if (mt.find()) return mt.group(2).charAt(0);
        // 2) custom bracket format: [Tag] L message
        Matcher mb = LOG_BRACKET.matcher(ln);
        if (mb.find()) return mb.group(1).charAt(0);
        // 3) brief format: L/Tag: message
        Matcher mr = LOG_BRIEF.matcher(ln);
        if (mr.find()) return mr.group(1).charAt(0);
        // Unknown
        return (char) 0;
    }

    private SpannableStringBuilder colorizeChunk(String chunk) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = chunk.split("\n");
        for (String ln : lines) out.append(colorizeLine(ln)).append('\n');
        return out;
    }

    private CharSequence colorizeLine(String ln) {
        CharSequence formatted = tryFormatThreadtime(ln);
        if (formatted != null) return formatted;

        SpannableStringBuilder sb = new SpannableStringBuilder(ln);
        Matcher mb = LOG_BRACKET.matcher(ln);
        if (mb.find()) {
            int letterStart = mb.start(1);
            int letterEnd = mb.end(1);
            char lvl = ln.charAt(letterStart);
            int color = levelColor(lvl);
            sb.setSpan(new BackgroundColorSpan(color), letterStart, letterEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(contrastingTextColor(color)), letterStart, letterEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int msgStart = Math.min(letterEnd + 1, sb.length());
            while (msgStart < sb.length() && Character.isWhitespace(sb.charAt(msgStart))) msgStart++;
            if (msgStart < sb.length()) {
                sb.setSpan(new ForegroundColorSpan(color), msgStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return sb;
        }
        return sb; // fallback: no special coloring
    }

    private static final Pattern LOG_THREADTIME = Pattern.compile(
            "^\\d{2}-\\d{2} (\\d{2}:\\d{2}:\\d{2})(?:\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):\\s*(.*)$"
    );

    private CharSequence tryFormatThreadtime(@NonNull String ln) {
        Matcher m = LOG_THREADTIME.matcher(ln);
        if (!m.find()) return null;
        String time = m.group(1); // HH:MM:SS
        char lvl = m.group(2).charAt(0);
        String tag = m.group(3).trim();
        String msg = m.group(4);

        SpannableStringBuilder out = new SpannableStringBuilder();
        int timeStart = out.length();
        out.append(time);
        int timeEnd = out.length();
        out.append(" ");
        int tagStart = out.length();
        out.append("[").append(tag).append("] ");
        int tagEnd = out.length();
        int lvlStart = out.length();
        out.append(String.valueOf(lvl));
        int lvlEnd = out.length();
        out.append(" ").append(msg);
        int levelColor = levelColor(lvl);
        out.setSpan(new ForegroundColorSpan(0xFFAAAAAA), timeStart, timeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(0xFFEEEEEE), tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(levelColor), lvlStart, lvlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(contrastingTextColor(levelColor)), lvlStart, lvlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int msgStart = lvlEnd + 1; 
        if (msgStart < out.length()) {
            out.setSpan(new ForegroundColorSpan(levelColor), msgStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private int contrastingTextColor(int bgColor) {
        int r = (bgColor >> 16) & 0xFF;
        int g = (bgColor >> 8) & 0xFF;
        int b = bgColor & 0xFF;
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luma > 150 ? Color.BLACK : Color.WHITE;
    }

    private int levelColor(char l) {
        return switch (l) {
            case 'V' -> Color.GRAY;
            case 'D' -> Color.CYAN;
            case 'I' -> Color.GREEN;
            case 'W' -> Color.YELLOW;
            case 'E' -> Color.RED;
            case 'F' -> Color.MAGENTA;
            default -> Color.WHITE;
        };
    }

    private static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView tv;
        LogViewHolder(@NonNull TextView t) { super(t); this.tv = t; }
    }

    private class LogAdapter extends RecyclerView.Adapter<LogViewHolder> {
        private final List<CharSequence> items = new ArrayList<>();

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setLineSpacing(dp(2), 1f);
            tv.setTextIsSelectable(true);
            tv.setHighlightColor(0x33FFFFFF);
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new LogViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            holder.tv.setText(items.get(position));
        }

        @Override
        public int getItemCount() { return items.size(); }

        void addAll(@NonNull List<CharSequence> more) {
            if (more.isEmpty()) return;
            int start = items.size();
            items.addAll(more);
            notifyItemRangeInserted(start, more.size());
        }

        void setItems(@NonNull List<CharSequence> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void clear() {
            items.clear();
            notifyDataSetChanged();
        }
    }


    private void smoothScrollToBottom() {
        int last = Math.max(0, logAdapter.getItemCount() - 1);
        recyclerView.smoothScrollToPosition(last);
    }
    private boolean isAtBottom() {
        int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();
        int total = logAdapter.getItemCount();
        return total == 0 || lastVisible >= total - 1;
    }
    private List<String> parseKeywords(String csv) {
        List<String> list = new ArrayList<>();
        for (String s : csv.split("[,;\\s]+")) if (!s.isEmpty()) list.add(s);
        return list;
    }

    private void initHistoryFiles(Context ctx) {
        File dir = ctx.getApplicationContext().getNoBackupFilesDir();
        historyFile = new File(dir, HISTORY_FILE_NAME);
        historyLockFile = new File(dir, HISTORY_LOCK_FILE_NAME);
    }

    private void restorePersistedHistory() {
        List<String> history = readPersistedHistory();
        if (history.isEmpty()) return;

        synchronized (logBuffer) {
            logBuffer.clear();
            for (String line : history) {
                if (logBuffer.size() >= MAX_BUFFER_LINES) logBuffer.pollFirst();
                logBuffer.addLast(line);
            }
        }

        List<CharSequence> items = new ArrayList<>();
        for (String line : history) {
            if (passesFilter(line)) {
                items.add(colorizeLine(line));
            }
        }
        logAdapter.setItems(items);
        if (autoScroll) recyclerView.post(this::smoothScrollToBottom);
    }

    private List<String> readPersistedHistory() {
        if (historyFile == null || historyLockFile == null || !historyFile.exists()) {
            return Collections.emptyList();
        }

        synchronized (historyLock) {
            try {
                ensureHistoryDirectory();
                try (RandomAccessFile lockRaf = new RandomAccessFile(historyLockFile, "rw");
                     FileChannel lockChannel = lockRaf.getChannel();
                     FileLock ignored = lockChannel.lock()) {
                    List<String> lines = readHistoryLinesFromDisk();
                    if (lines.size() > MAX_BUFFER_LINES) {
                        lines = new ArrayList<>(lines.subList(lines.size() - MAX_BUFFER_LINES, lines.size()));
                    }
                    return lines;
                }
            } catch (Exception ignored) {
                return Collections.emptyList();
            }
        }
    }

    private void appendPersistedHistory(@NonNull List<String> lines) {
        if (lines.isEmpty()) return;
        if (historyFile == null || historyLockFile == null) return;

        synchronized (historyLock) {
            try {
                ensureHistoryDirectory();
                try (RandomAccessFile lockRaf = new RandomAccessFile(historyLockFile, "rw");
                     FileChannel lockChannel = lockRaf.getChannel();
                     FileLock ignored = lockChannel.lock()) {
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(historyFile, true), StandardCharsets.UTF_8))) {
                        for (String line : lines) {
                            writer.write(sanitizeHistoryLine(line));
                            writer.newLine();
                        }
                    }

                    pendingHistoryTrimLines += lines.size();
                    if (pendingHistoryTrimLines >= HISTORY_TRIM_INTERVAL_LINES) {
                        trimPersistedHistoryLocked();
                        pendingHistoryTrimLines = 0;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void clearPersistedHistory() {
        if (historyFile == null || historyLockFile == null) return;

        synchronized (historyLock) {
            try {
                ensureHistoryDirectory();
                try (RandomAccessFile lockRaf = new RandomAccessFile(historyLockFile, "rw");
                     FileChannel lockChannel = lockRaf.getChannel();
                     FileLock ignored = lockChannel.lock()) {
                    try (FileOutputStream outputStream = new FileOutputStream(historyFile, false)) {
                        outputStream.getFD().sync();
                    }
                    pendingHistoryTrimLines = 0;
                }
            } catch (Exception ignored) {}
        }
    }

    private void trimPersistedHistoryLocked() throws java.io.IOException {
        List<String> lines = readHistoryLinesFromDisk();
        if (lines.size() <= MAX_BUFFER_LINES) return;

        int fromIndex = lines.size() - MAX_BUFFER_LINES;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(historyFile, false), StandardCharsets.UTF_8))) {
            for (int i = fromIndex; i < lines.size(); i++) {
                writer.write(lines.get(i));
                writer.newLine();
            }
        }
    }

    private List<String> readHistoryLinesFromDisk() throws java.io.IOException {
        if (!historyFile.exists()) return Collections.emptyList();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(historyFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private void ensureHistoryDirectory() {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private String sanitizeHistoryLine(@NonNull String line) {
        return line.replace('\r', ' ').replace('\n', ' ');
    }

    private void restoreState(SharedPreferences sp) {
        int x = sp.getInt("log_overlay_x", -1);
        int y = sp.getInt("log_overlay_y", -1);
        int w = sp.getInt("log_overlay_w", -1);
        int h = sp.getInt("log_overlay_h", -1);
        boolean fb = sp.getBoolean("log_overlay_filterbar", false);
        minimized = sp.getBoolean("log_overlay_minimized", false);
        int bx = sp.getInt("log_overlay_bx", -1);
        int by = sp.getInt("log_overlay_by", -1);

        post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
            if (w > 0) lp.width = w;
            if (h > 0) lp.height = h;
            if (x >= 0) lp.leftMargin = x;
            if (y >= 0) lp.topMargin = y;
            overlayContainer.setLayoutParams(lp);
            filterBar.setVisibility(fb ? VISIBLE : GONE);

            if (minimizeBubble != null) {
                FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) minimizeBubble.getLayoutParams();
                blp.gravity = Gravity.TOP | Gravity.START;
                if (bx >= 0) blp.leftMargin = bx;
                if (by >= 0) blp.topMargin = by;
                minimizeBubble.setLayoutParams(blp);
            }
            setMinimized(minimized);
        });
    }
    private int dpToPx(int dp) { return (int) dp(dp); }
    private float dp(float v){ return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,getResources().getDisplayMetrics()); }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private void setMinimized(boolean value) {
        minimized = value;
        overlayContainer.setVisibility(value ? GONE : VISIBLE);
        if (cornerBottomLeft != null) cornerBottomLeft.setVisibility(value ? GONE : VISIBLE);
        if (cornerBottomRight != null) cornerBottomRight.setVisibility(value ? GONE : VISIBLE);
        if (bottomResizeBar != null) bottomResizeBar.setVisibility(value ? GONE : VISIBLE);
        minimizeBubble.setVisibility(value ? VISIBLE : GONE);
        if (value && minimizeBubble != null) {
            post(() -> {
                FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) minimizeBubble.getLayoutParams();
                blp.gravity = Gravity.TOP | Gravity.START;
                int maxX = Math.max(0, getWidth() - minimizeBubble.getWidth());
                int maxY = Math.max(0, getHeight() - minimizeBubble.getHeight());
                blp.leftMargin = clamp(blp.leftMargin, 0, maxX);
                blp.topMargin = clamp(blp.topMargin, 0, maxY);
                minimizeBubble.setLayoutParams(blp);
                minimizeBubble.bringToFront();
            });
        }
        saveState();
    }

    private void saveState() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayContainer.getLayoutParams();
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt("log_overlay_x", lp.leftMargin);
        ed.putInt("log_overlay_y", lp.topMargin);
        ed.putInt("log_overlay_w", lp.width > 0 ? lp.width : overlayContainer.getWidth());
        ed.putInt("log_overlay_h", lp.height > 0 ? lp.height : overlayContainer.getHeight());
        ed.putBoolean("log_overlay_filterbar", filterBar.getVisibility() == VISIBLE);
        ed.putBoolean("log_overlay_minimized", minimized);
        if (minimizeBubble != null) {
            FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) minimizeBubble.getLayoutParams();
            ed.putInt("log_overlay_bx", blp.leftMargin);
            ed.putInt("log_overlay_by", blp.topMargin);
        }
        ed.apply();
    }

    public void show() {
        setVisibility(VISIBLE);
        start();
    }

    public void hide() {
        stop();
        setVisibility(GONE);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!isTouchOnInteractiveArea((int) ev.getX(), (int) ev.getY())) {
                return false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTouchOnInteractiveArea(int x, int y) {
        Rect r = new Rect();
        if (minimized) {
            if (minimizeBubble != null && minimizeBubble.getVisibility() == VISIBLE) {
                minimizeBubble.getHitRect(r);
                return r.contains(x, y);
            }
            return false;
        }
        if (overlayContainer != null && overlayContainer.getVisibility() == VISIBLE) {
            overlayContainer.getHitRect(r);
            if (r.contains(x, y)) return true;
        }
        if (cornerBottomLeft != null && cornerBottomLeft.getVisibility() == VISIBLE) {
            cornerBottomLeft.getHitRect(r);
            if (r.contains(x, y)) return true;
        }
        if (cornerBottomRight != null && cornerBottomRight.getVisibility() == VISIBLE) {
            cornerBottomRight.getHitRect(r);
            if (r.contains(x, y)) return true;
        }
        return false;
    }
}
