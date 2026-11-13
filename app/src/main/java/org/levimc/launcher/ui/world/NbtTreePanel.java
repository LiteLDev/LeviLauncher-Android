package org.levimc.launcher.ui.world;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.ui.dialogs.LauncherDialogBuilder;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class NbtTreePanel extends LinearLayout {
    public interface Host {
        void changed();

        void snbt(NbtTag tag, Consumer<NbtTag> accept);

        void copy(NbtTag tag, Consumer<NbtTag> accept);

        void error(String message);

        void search(Runnable operation, Runnable accept);
    }

    private static final class Path {
        final NbtTag tag;
        final NbtTag parent;
        final Object key;
        final String label;

        Path(NbtTag tag, NbtTag parent, Object key, String label) {
            this.tag = tag;
            this.parent = parent;
            this.key = key;
            this.label = label;
        }
    }

    private final Host host;
    private final List<Path> trail = new ArrayList<>(), rows = new ArrayList<>();
    private final TextView breadcrumb, summary;
    private final EditText search;
    private final Adapter adapter = new Adapter();
    private final RecyclerView list;
    private final ImageView upButton;
    private NbtTag root;
    private int visibleLimit = 256;
    private int searchVersion;
    private Runnable searchJob;
    private final Consumer<NbtTag> rootChanged;

    public NbtTreePanel(Context c, NbtTag root, Host host, Consumer<NbtTag> rootChanged) {
        super(c);
        this.root = root;
        this.host = host;
        this.rootChanged = rootChanged;
        setOrientation(VERTICAL);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(WorldUi.dp(c, 4), 0, WorldUi.dp(c, 8), 0);
        upButton = WorldUi.backButton(c, this::up);
        upButton.setContentDescription("Parent tag");
        upButton.setTooltipText("Parent tag");
        bar.addView(upButton, new LayoutParams(WorldUi.dp(c, 40), WorldUi.dp(c, 44)));
        breadcrumb = WorldUi.text(c, "/", 12, true);
        breadcrumb.setSingleLine(true);
        breadcrumb.setGravity(Gravity.CENTER_VERTICAL);
        breadcrumb.setPadding(0, 0, WorldUi.dp(c, 8), 0);
        breadcrumb.setTooltipText("Edit as SNBT");
        breadcrumb.setOnClickListener(
                v -> host.snbt(current(), tag -> replace(trail.get(trail.size() - 1), tag)));
        HorizontalScrollView path = new HorizontalScrollView(c);
        path.setHorizontalScrollBarEnabled(false);
        path.addView(breadcrumb, new ViewGroup.LayoutParams(-2, -1));
        bar.addView(path, new LayoutParams(0, -1, 1));
        search = WorldUi.input(c, "Search tags", "", false);
        bar.addView(search, new LayoutParams(WorldUi.dp(c, 144), WorldUi.dp(c, 36)));
        bar.addView(
                WorldUi.iconButton(c, R.drawable.ic_add, "Add tag", this::add),
                new LayoutParams(WorldUi.dp(c, 40), WorldUi.dp(c, 44)));
        addView(bar, new LayoutParams(-1, WorldUi.dp(c, 48)));
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

                    public void onTextChanged(CharSequence s, int a, int b, int d) {
                        refresh();
                    }

                    public void afterTextChanged(Editable e) {}
                });
        list = new RecyclerView(c);
        list.setLayoutManager(new LinearLayoutManager(c));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(WorldUi.dp(c, 12), WorldUi.dp(c, 4), WorldUi.dp(c, 12), WorldUi.dp(c, 8));
        androidx.recyclerview.widget.DividerItemDecoration divider =
                new androidx.recyclerview.widget.DividerItemDecoration(c, VERTICAL);
        android.graphics.drawable.GradientDrawable line =
                WorldUi.background(c, WorldUi.color(c, R.color.surface_variant), 0);
        line.setSize(1, WorldUi.dp(c, 1));
        divider.setDrawable(line);
        list.addItemDecoration(divider);
        list.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView view, int dx, int dy) {
                        if (dy > 0
                                && search.getText().length() == 0
                                && !view.canScrollVertically(1)
                                && rows.size() < childCount(current())) {
                            visibleLimit += 256;
                            refresh();
                        }
                    }
                });
        addView(list, new LayoutParams(-1, 0, 1));
        summary = WorldUi.text(c, "", 11, true);
        summary.setPadding(
                WorldUi.dp(c, 16), WorldUi.dp(c, 4), WorldUi.dp(c, 16), WorldUi.dp(c, 8));
        addView(summary);
        trail.add(new Path(root, null, null, "/"));
        refresh();
    }

    public NbtTag root() {
        return root;
    }

    private NbtTag current() {
        return trail.get(trail.size() - 1).tag;
    }

    public boolean up() {
        if (trail.size() <= 1) return false;
        trail.remove(trail.size() - 1);
        visibleLimit = 256;
        search.setText("");
        refresh();
        return true;
    }

    private Path child(Path parent, NbtTag tag, Object key) {
        String path =
                parent.tag.getType() == NbtTag.TAG_LIST
                        ? parent.label + "[" + key + "]"
                        : parent.label + (parent.label.endsWith("/") ? "" : "/") + key;
        return new Path(tag, parent.tag, key, path);
    }

    private int childCount(NbtTag tag) {
        return tag.getType() == NbtTag.TAG_COMPOUND
                ? tag.getCompound().size()
                : tag.getType() == NbtTag.TAG_LIST ? tag.getList().size() : 0;
    }

    private void refresh() {
        if (trail.isEmpty()) return;
        if (searchJob != null) removeCallbacks(searchJob);
        int version = ++searchVersion;
        Path current = trail.get(trail.size() - 1);
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        breadcrumb.setText(current.label);
        upButton.setEnabled(trail.size() > 1);
        upButton.setAlpha(trail.size() > 1 ? 1f : .3f);
        if (!query.isEmpty()) {
            summary.setVisibility(VISIBLE);
            summary.setText("Searching…");
            searchJob =
                    () -> {
                        List<Path> found = new ArrayList<>();
                        host.search(
                                () -> find(current, query, 0, found),
                                () -> {
                                    if (version != searchVersion) return;
                                    rows.clear();
                                    rows.addAll(found);
                                    summary.setText(
                                            found.size()
                                                    + (found.size() >= 1000
                                                            ? "+ matches"
                                                            : " matches"));
                                    adapter.notifyDataSetChanged();
                                    list.scrollToPosition(0);
                                });
                    };
            postDelayed(searchJob, 350);
            return;
        }
        rows.clear();
        NbtTag tag = current.tag;
        if (tag.getType() == NbtTag.TAG_COMPOUND) {
            for (java.util.Map.Entry<String, NbtTag> entry : tag.getCompound().entrySet()) {
                if (rows.size() >= visibleLimit) break;
                rows.add(child(current, entry.getValue(), entry.getKey()));
            }
        } else if (tag.getType() == NbtTag.TAG_LIST) {
            for (int i = 0; i < Math.min(visibleLimit, tag.getList().size()); i++)
                rows.add(child(current, tag.getList().get(i), i));
        } else rows.add(current);
        summary.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        summary.setText("No tags");
        adapter.notifyDataSetChanged();
    }

    private void find(Path path, String query, int depth, List<Path> found) {
        if (found.size() >= 1000 || depth > 128 || Thread.currentThread().isInterrupted()) return;
        if (path.tag.getType() == NbtTag.TAG_COMPOUND) {
            for (java.util.Map.Entry<String, NbtTag> entry : path.tag.getCompound().entrySet()) {
                if (found.size() >= 1000 || Thread.currentThread().isInterrupted()) return;
                findChild(child(path, entry.getValue(), entry.getKey()), query, depth, found);
            }
        } else if (path.tag.getType() == NbtTag.TAG_LIST) {
            for (int i = 0; i < path.tag.getList().size(); i++) {
                if (found.size() >= 1000 || Thread.currentThread().isInterrupted()) return;
                findChild(child(path, path.tag.getList().get(i), i), query, depth, found);
            }
        }
    }

    private void findChild(Path path, String query, int depth, List<Path> found) {
        if (path.label.toLowerCase(Locale.ROOT).contains(query)
                || preview(path.tag).toLowerCase(Locale.ROOT).contains(query)) found.add(path);
        if (path.tag.getType() == NbtTag.TAG_LIST || path.tag.getType() == NbtTag.TAG_COMPOUND)
            find(path, query, depth + 1, found);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (searchJob != null) removeCallbacks(searchJob);
        searchVersion++;
        super.onDetachedFromWindow();
    }

    public static String preview(NbtTag tag) {
        return switch (tag.getType()) {
            case NbtTag.TAG_COMPOUND -> tag.getCompound().size() + " entries";
            case NbtTag.TAG_LIST ->
                    tag.getList().size() + " items · " + NbtTag.getTypeName(tag.getListType());
            case NbtTag.TAG_BYTE_ARRAY -> tag.getByteArray().length + " bytes";
            case NbtTag.TAG_INT_ARRAY -> tag.getIntArray().length + " integers";
            case NbtTag.TAG_LONG_ARRAY -> tag.getLongArray().length + " longs";
            default -> String.valueOf(tag.getValue());
        };
    }

    private void open(Path row) {
        if (row.tag.getType() == NbtTag.TAG_COMPOUND || row.tag.getType() == NbtTag.TAG_LIST) {
            trail.add(row);
            visibleLimit = 256;
            search.setText("");
            refresh();
        } else if (row.tag.getType() == NbtTag.TAG_BYTE_ARRAY
                || row.tag.getType() == NbtTag.TAG_INT_ARRAY
                || row.tag.getType() == NbtTag.TAG_LONG_ARRAY)
            host.snbt(row.tag, tag -> replace(row, tag));
        else editScalar(row);
    }

    private void editScalar(Path row) {
        Context c = getContext();
        LinearLayout form = WorldUi.column(c, 16);
        EditText value =
                WorldUi.input(
                        c,
                        NbtTag.getTypeName(row.tag.getType()),
                        String.valueOf(row.tag.getValue()),
                        row.tag.getType() == NbtTag.TAG_STRING);
        form.addView(value);
        TextView error = WorldUi.text(c, "", 12, true);
        form.addView(error);
        AlertDialog dialog =
                WorldUi.show(
                        c,
                        new LauncherDialogBuilder(c)
                                .setTitle(
                                        row.tag.getName().isEmpty()
                                                ? "Edit " + NbtTag.getTypeName(row.tag.getType())
                                                : row.tag.getName())
                                .setView(form)
                                .setPositiveButton("Apply", null)
                                .setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                String text = value.getText().toString();
                                Object parsed =
                                        switch (row.tag.getType()) {
                                            case NbtTag.TAG_BYTE -> Byte.parseByte(text.trim());
                                            case NbtTag.TAG_SHORT -> Short.parseShort(text.trim());
                                            case NbtTag.TAG_INT -> Integer.parseInt(text.trim());
                                            case NbtTag.TAG_LONG -> Long.parseLong(text.trim());
                                            case NbtTag.TAG_FLOAT -> Float.parseFloat(text.trim());
                                            case NbtTag.TAG_DOUBLE ->
                                                    Double.parseDouble(text.trim());
                                            default -> text;
                                        };
                                row.tag.setValue(parsed);
                                host.changed();
                                refresh();
                                dialog.dismiss();
                            } catch (IllegalArgumentException ex) {
                                error.setText(
                                        "Value is outside the range of "
                                                + NbtTag.getTypeName(row.tag.getType()));
                            }
                        });
    }

    private void actions(Path row) {
        String[] actions = {"Edit / open", "Rename", "Duplicate", "Replace with SNBT", "Delete"};
        WorldUi.show(
                getContext(),
                new LauncherDialogBuilder(getContext())
                        .setTitle(row.tag.getName().isEmpty() ? row.label : row.tag.getName())
                        .setItems(
                                actions,
                                (d, which) -> {
                                    if (which == 0) open(row);
                                    else if (which == 1) rename(row);
                                    else if (which == 2)
                                        host.copy(
                                                row.tag,
                                                tag -> {
                                                    if (row.parent == null) {
                                                        host.error("The root cannot be duplicated");
                                                        return;
                                                    }
                                                    if (row.parent.getType() == NbtTag.TAG_LIST) {
                                                        row.parent.getList().add(tag);
                                                        host.changed();
                                                        refresh();
                                                    } else addNamed(row.parent, tag);
                                                });
                                    else if (which == 3)
                                        host.snbt(row.tag, tag -> replace(row, tag));
                                    else if (which == 4) remove(row);
                                }));
    }

    private void rename(Path row) {
        if (row.parent == null) {
            host.error("The root name is preserved by the file format");
            return;
        }
        if (row.parent.getType() != NbtTag.TAG_COMPOUND) {
            host.error("List entries use numeric indices");
            return;
        }
        Context c = getContext();
        EditText input = WorldUi.input(c, "Tag name", row.tag.getName(), false);
        LinearLayout form = WorldUi.column(c, 16);
        form.addView(input);
        AlertDialog dialog =
                WorldUi.show(
                        c,
                        new LauncherDialogBuilder(c)
                                .setTitle("Rename tag")
                                .setView(form)
                                .setPositiveButton("Rename", null)
                                .setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            String name = input.getText().toString();
                            if (!name.equals(row.key)
                                    && row.parent.getCompound().containsKey(name)) {
                                input.setError("Name already exists");
                                return;
                            }
                            row.parent.getCompound().remove(row.key);
                            row.tag.setName(name);
                            row.parent.putTag(name, row.tag);
                            host.changed();
                            refresh();
                            dialog.dismiss();
                        });
    }

    private void remove(Path row) {
        if (row.parent == null) {
            host.error("The root cannot be deleted");
            return;
        }
        WorldUi.show(
                getContext(),
                new LauncherDialogBuilder(getContext())
                        .setTitle("Delete tag?")
                        .setMessage(row.label)
                        .setPositiveButton(
                                "Delete",
                                (d, w) -> {
                                    if (row.parent.getType() == NbtTag.TAG_COMPOUND)
                                        row.parent.getCompound().remove(row.key);
                                    else row.parent.getList().remove((int) row.key);
                                    host.changed();
                                    refresh();
                                })
                        .setNegativeButton("Cancel", null));
    }

    private void replace(Path row, NbtTag tag) {
        if (row.parent != null
                && row.parent.getType() == NbtTag.TAG_LIST
                && tag.getType() != row.parent.getListType()) {
            host.error("List entries must stay " + NbtTag.getTypeName(row.parent.getListType()));
            return;
        }
        tag.setName(row.tag.getName());
        if (row.parent == null) {
            root = tag;
            rootChanged.accept(tag);
            trail.clear();
            trail.add(new Path(tag, null, null, "/"));
        } else if (row.parent.getType() == NbtTag.TAG_COMPOUND)
            row.parent.putTag((String) row.key, tag);
        else row.parent.getList().set((int) row.key, tag);
        if (trail.get(trail.size() - 1) == row && row.parent != null)
            trail.remove(trail.size() - 1);
        host.changed();
        refresh();
    }

    private void add() {
        NbtTag parent = current();
        if (parent.getType() != NbtTag.TAG_COMPOUND && parent.getType() != NbtTag.TAG_LIST) {
            host.error("Select a compound or list to add a tag");
            return;
        }
        if (parent.getType() == NbtTag.TAG_LIST && parent.getListType() != NbtTag.TAG_END) {
            insert(parent, empty(parent.getListType()));
            return;
        }
        String[] types = new String[12];
        for (int i = 0; i < 12; i++) types[i] = NbtTag.getTypeName((byte) (i + 1));
        WorldUi.show(
                getContext(),
                new LauncherDialogBuilder(getContext())
                        .setTitle("Tag type")
                        .setItems(types, (d, i) -> insert(parent, empty((byte) (i + 1)))));
    }

    private void insert(NbtTag parent, NbtTag tag) {
        if (parent.getType() == NbtTag.TAG_COMPOUND) addNamed(parent, tag);
        else {
            if (parent.getListType() == NbtTag.TAG_END) parent.setListType(tag.getType());
            tag.setName("");
            parent.getList().add(tag);
            host.changed();
            refresh();
        }
    }

    private void addNamed(NbtTag parent, NbtTag tag) {
        LinearLayout form = WorldUi.column(getContext(), 16);
        EditText input = WorldUi.input(getContext(), "Tag name", tag.getName(), false);
        form.addView(input);
        AlertDialog dialog =
                WorldUi.show(
                        getContext(),
                        new LauncherDialogBuilder(getContext())
                                .setTitle("Add tag")
                                .setView(form)
                                .setPositiveButton("Add", null)
                                .setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            String name = input.getText().toString();
                            if (parent.getCompound().containsKey(name)) {
                                input.setError("Name already exists");
                                return;
                            }
                            tag.setName(name);
                            parent.putTag(name, tag);
                            host.changed();
                            refresh();
                            dialog.dismiss();
                        });
    }

    private static NbtTag empty(byte type) {
        Object value =
                switch (type) {
                    case NbtTag.TAG_BYTE -> (byte) 0;
                    case NbtTag.TAG_SHORT -> (short) 0;
                    case NbtTag.TAG_INT -> 0;
                    case NbtTag.TAG_LONG -> 0L;
                    case NbtTag.TAG_FLOAT -> 0f;
                    case NbtTag.TAG_DOUBLE -> 0d;
                    case NbtTag.TAG_STRING -> "";
                    case NbtTag.TAG_BYTE_ARRAY -> new byte[0];
                    case NbtTag.TAG_INT_ARRAY -> new int[0];
                    case NbtTag.TAG_LONG_ARRAY -> new long[0];
                    case NbtTag.TAG_LIST -> new ArrayList<NbtTag>();
                    case NbtTag.TAG_COMPOUND -> new LinkedHashMap<String, NbtTag>();
                    default -> null;
                };
        return new NbtTag(type, "", value);
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = getContext();
            LinearLayout row = new LinearLayout(c);
            row.setLayoutParams(
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(
                    WorldUi.dp(c, 8), WorldUi.dp(c, 10), WorldUi.dp(c, 4), WorldUi.dp(c, 10));
            row.setMinimumHeight(WorldUi.dp(c, 60));
            LinearLayout column = WorldUi.column(c, 0);
            TextView name = WorldUi.text(c, "", 13, false);
            name.setSingleLine(false);
            name.setEllipsize(null);
            TextView typeLabel = WorldUi.text(c, "", 10, true);
            typeLabel.setPadding(0, WorldUi.dp(c, 3), 0, 0);
            column.addView(name);
            column.addView(typeLabel);
            row.addView(column, new LinearLayout.LayoutParams(0, -2, 1.25f));
            TextView value = WorldUi.text(c, "", 13, true);
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            value.setSingleLine(false);
            value.setEllipsize(null);
            value.setPadding(WorldUi.dp(c, 16), 0, WorldUi.dp(c, 8), 0);
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView arrow = new ImageView(c);
            arrow.setImageResource(R.drawable.ic_arrow_down);
            arrow.setRotation(270);
            arrow.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            WorldUi.color(c, R.color.text_secondary)));
            row.addView(arrow, new LayoutParams(WorldUi.dp(c, 16), WorldUi.dp(c, 16)));
            android.util.TypedValue background = new android.util.TypedValue();
            c.getTheme()
                    .resolveAttribute(android.R.attr.selectableItemBackground, background, true);
            row.setBackgroundResource(background.resourceId);
            return new Holder(row, name, value, typeLabel, arrow);
        }

        public void onBindViewHolder(Holder holder, int position) {
            Path row = rows.get(position);
            String name =
                    row.parent != null && row.parent.getType() == NbtTag.TAG_LIST
                            ? "[" + row.key + "]"
                            : row.tag.getName();
            holder.name.setText(name.isEmpty() ? "Root" : name);
            String value = preview(row.tag);
            holder.value.setText(
                    value.length() > 512 ? value.length() + " characters · tap to edit" : value);
            holder.type.setText(
                    search.getText().length() > 0
                            ? row.label
                            : NbtTag.getTypeName(row.tag.getType()));
            holder.type.setSingleLine(false);
            holder.type.setEllipsize(null);
            boolean container =
                    row.tag.getType() == NbtTag.TAG_COMPOUND
                            || row.tag.getType() == NbtTag.TAG_LIST;
            holder.arrow.setVisibility(container ? VISIBLE : INVISIBLE);
            holder.itemView.setOnClickListener(v -> open(row));
            holder.itemView.setOnLongClickListener(
                    v -> {
                        actions(row);
                        return true;
                    });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, value, type;
        final ImageView arrow;

        Holder(View view, TextView name, TextView value, TextView type, ImageView arrow) {
            super(view);
            this.name = name;
            this.value = value;
            this.type = type;
            this.arrow = arrow;
        }
    }
}
