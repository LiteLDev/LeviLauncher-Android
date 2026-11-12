package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.FlatWorldGenerator;
import org.levimc.launcher.core.content.FlatWorldGenerator.BlockLayer;
import org.levimc.launcher.databinding.ActivityCustomFlatWorldBinding;
import org.levimc.launcher.ui.widget.CatalogSelect;
import org.levimc.launcher.ui.widget.UiKit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomFlatWorldActivity extends BaseActivity {

    public static final String EXTRA_WORLDS_DIRECTORY = "worlds_directory";

    private ActivityCustomFlatWorldBinding binding;
    private LayersAdapter layersAdapter;
    private List<BlockLayer> layers;
    private File worldsDirectory;
    private ExecutorService executor;
    private ItemTouchHelper itemTouchHelper;
    private CatalogSelect biomeSelect, gameModeSelect;

    private static final String[] BIOME_NAMES = {
            "Plains", "Desert", "Mountains", "Forest", "Taiga", "Swamp", "River", "Nether Wastes",
            "The End", "Frozen Ocean", "Frozen River", "Snowy Plains", "Snowy Mountains", "Mushroom Fields",
            "Beach", "Jungle", "Sparse Jungle", "Deep Ocean", "Stony Shore", "Snowy Beach",
            "Birch Forest", "Dark Forest", "Snowy Taiga", "Old Growth Pine Taiga", "Windswept Forest",
            "Savanna", "Savanna Plateau", "Badlands", "Wooded Badlands", "Warm Ocean"
    };

    private static final int[] BIOME_IDS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 21, 23, 24, 25, 26,
            27, 29, 30, 32, 34, 35, 36, 37, 38, 44
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCustomFlatWorldBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();

        String worldsPath = getIntent().getStringExtra(EXTRA_WORLDS_DIRECTORY);
        if (worldsPath == null) {
            Toast.makeText(this, R.string.invalid_worlds_directory, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        worldsDirectory = new File(worldsPath);
        layers = new ArrayList<>(FlatWorldGenerator.getDefaultLayers());
        if (savedInstanceState != null) {
            ArrayList<String> names = savedInstanceState.getStringArrayList("flat_blocks");
            int[] counts = savedInstanceState.getIntArray("flat_counts");
            if (names != null && counts != null && names.size() == counts.length) {
                layers.clear();
                for (int i = 0; i < counts.length; i++) layers.add(new BlockLayer(names.get(i), Math.max(1, Math.min(256, counts[i]))));
            }
        }
        setupUI();
        if (savedInstanceState != null) {
            biomeSelect.setSelected(savedInstanceState.getInt("flat_biome", 0));
            gameModeSelect.setSelected(savedInstanceState.getInt("flat_game_mode", 1));
        }
    }

    private void setupUI() {
        biomeSelect = new CatalogSelect(binding.biomeSpinner, getString(R.string.biome));
        biomeSelect.setItems(java.util.Arrays.asList(BIOME_NAMES), 0);
        gameModeSelect = new CatalogSelect(binding.gameModeSpinner, getString(R.string.game_mode));
        gameModeSelect.setItems(java.util.Arrays.asList(getString(R.string.flat_game_survival),
                getString(R.string.flat_game_creative), getString(R.string.flat_game_adventure)), 1);
        UiKit.applyPrimary(binding.createWorldButton);

        layersAdapter = new LayersAdapter();
        binding.layersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.layersRecyclerView.setAdapter(layersAdapter);

        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false;
                }
                Collections.swap(layers, from, to);
                layersAdapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                layersAdapter.notifyItemRangeChanged(0, layersAdapter.getItemCount());
                updateLayersUi();
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                removeLayer(viewHolder.getBindingAdapterPosition());
            }
        });
        itemTouchHelper.attachToRecyclerView(binding.layersRecyclerView);

        binding.addLayerButton.setOnClickListener(v -> {
            layers.add(new BlockLayer("minecraft:stone", 1));
            int position = layers.size() - 1;
            layersAdapter.notifyItemInserted(position);
            updateLayersUi();
            binding.layersRecyclerView.post(() -> binding.layersRecyclerView.smoothScrollToPosition(position));
        });

        binding.createWorldButton.setOnClickListener(v -> createWorld());
        updateLayersUi();
    }

    private void removeLayer(int position) {
        if (position < 0 || position >= layers.size()) {
            return;
        }
        layers.remove(position);
        layersAdapter.notifyItemRemoved(position);
        if (position < layersAdapter.getItemCount()) {
            layersAdapter.notifyItemRangeChanged(position, layersAdapter.getItemCount() - position);
        }
        updateLayersUi();
    }

    private void updateLayersUi() {
        int count = layers.size();
        binding.layersSummary.setText(getResources().getQuantityString(R.plurals.flat_layers_count, count, count));
        binding.emptyLayersState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        binding.layersRecyclerView.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private void saveAllLayerValues() {
        for (int i = 0; i < binding.layersRecyclerView.getChildCount(); i++) {
            View child = binding.layersRecyclerView.getChildAt(i);
            RecyclerView.ViewHolder holder = binding.layersRecyclerView.getChildViewHolder(child);
            if (holder != null) {
                int position = holder.getBindingAdapterPosition();
                if (position >= 0 && position < layers.size()) {
                    EditText countEdit = child.findViewById(R.id.count_edit);
                    EditText blockEdit = child.findViewById(R.id.block_edit);
                    if (countEdit != null) {
                        try {
                            int count = Integer.parseInt(countEdit.getText().toString());
                            layers.get(position).count = Math.max(1, Math.min(count, 256));
                        } catch (NumberFormatException e) {
                            layers.get(position).count = 1;
                        }
                    }
                    if (blockEdit != null) {
                        String blockName = blockEdit.getText().toString().trim();
                        if (!blockName.isEmpty()) {
                            layers.get(position).blockName = blockName;
                        }
                    }
                }
            }
        }
    }

    private void createWorld() {
        binding.getRoot().clearFocus();

        String worldName = binding.worldNameEdit.getText().toString().trim();
        if (worldName.isEmpty()) {
            Toast.makeText(this, R.string.enter_world_name, Toast.LENGTH_SHORT).show();
            return;
        }

        if (layers.isEmpty()) {
            Toast.makeText(this, R.string.add_at_least_one_layer, Toast.LENGTH_SHORT).show();
            return;
        }

        saveAllLayerValues();

        binding.createWorldButton.setEnabled(false);
        binding.createWorldButton.setAlpha(0.45f);
        binding.loadingProgress.setVisibility(View.VISIBLE);

        int biomeIndex = biomeSelect.getSelected();
        int biomeId = biomeIndex < BIOME_IDS.length ? BIOME_IDS[biomeIndex] : 1;
        int gameMode = gameModeSelect.getSelected();

        List<BlockLayer> layersCopy = new ArrayList<>();
        for (BlockLayer layer : layers) {
            layersCopy.add(new BlockLayer(layer.blockName, layer.count));
        }

        executor.execute(() -> {
            try {
                FlatWorldGenerator.generateFlatWorld(worldsDirectory, worldName, layersCopy, biomeId, gameMode);
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.world_created_successfully, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.createWorldButton.setEnabled(true);
                    binding.createWorldButton.setAlpha(1f);
                    Toast.makeText(this, getString(R.string.failed_to_create_world) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (layers == null) return;
        saveAllLayerValues();
        ArrayList<String> names = new ArrayList<>();
        int[] counts = new int[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            names.add(layers.get(i).blockName);
            counts[i] = layers.get(i).count;
        }
        outState.putStringArrayList("flat_blocks", names);
        outState.putIntArray("flat_counts", counts);
        if (biomeSelect != null) outState.putInt("flat_biome", biomeSelect.getSelected());
        if (gameModeSelect != null) outState.putInt("flat_game_mode", gameModeSelect.getSelected());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }

    private class LayersAdapter extends RecyclerView.Adapter<LayersAdapter.LayerViewHolder> {

        @NonNull
        @Override
        public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_flat_layer, parent, false);
            return new LayerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
            holder.bind(layers.get(position), position);
        }

        @Override
        public int getItemCount() {
            return layers.size();
        }

        class LayerViewHolder extends RecyclerView.ViewHolder {
            final EditText blockEdit;
            final EditText countEdit;
            final TextView layerNumber;
            final View dragHandle;
            final View deleteButton;
            private boolean isBinding;

            LayerViewHolder(View itemView) {
                super(itemView);
                blockEdit = itemView.findViewById(R.id.block_edit);
                countEdit = itemView.findViewById(R.id.count_edit);
                layerNumber = itemView.findViewById(R.id.layer_number);
                dragHandle = itemView.findViewById(R.id.drag_handle);
                deleteButton = itemView.findViewById(R.id.delete_layer_button);

                dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(this);
                        return true;
                    }
                    return false;
                });

                deleteButton.setOnClickListener(v -> removeLayer(getBindingAdapterPosition()));

                blockEdit.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (isBinding) {
                            return;
                        }
                        int adapterPos = getBindingAdapterPosition();
                        if (adapterPos >= 0 && adapterPos < layers.size()) {
                            String blockName = s.toString().trim();
                            if (!blockName.isEmpty()) {
                                layers.get(adapterPos).blockName = blockName;
                            }
                        }
                    }
                });

                countEdit.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (isBinding) {
                            return;
                        }
                        int adapterPos = getBindingAdapterPosition();
                        if (adapterPos >= 0 && adapterPos < layers.size()) {
                            try {
                                int count = Integer.parseInt(s.toString());
                                layers.get(adapterPos).count = Math.max(1, Math.min(count, 256));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                });
            }

            void bind(BlockLayer layer, int position) {
                isBinding = true;
                layerNumber.setText(String.valueOf(position + 1));
                blockEdit.setText(layer.blockName);
                countEdit.setText(String.valueOf(layer.count));
                isBinding = false;
            }
        }
    }
}
