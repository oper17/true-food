package com.barelabel.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.barelabel.app.model.Suggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FoodAutoCompleteManager {

    /** Fired when the user taps an autocomplete suggestion (carries the USDA fdcId). */
    public interface OnSuggestionSelected {
        void onSelected(Suggestion suggestion);
    }

    /** Fired when the user taps a recent-search bubble. */
    public interface OnRecentSearchSelected {
        void onSelected(String query);
    }

    private final Context context;
    private final SuggestionProvider suggestionProvider;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ListPopupWindow popupWindow;
    private ArrayAdapter<Suggestion> adapter;
    private OnSuggestionSelected suggestionSelectedListener;
    private Runnable searchRunnable;
    private PopupWindow recentsPopup;
    private OnRecentSearchSelected recentSearchListener;

    // Guard flag to suppress auto-complete triggers during programmatic edits/selections
    private boolean isSuppressingSuggestions = false;

    /**
     * Monotonic request generation. Bumped every time a new keystroke (or a
     * selection/dismiss) supersedes the previous request, so a slow USDA
     * response can never overwrite results for newer text. The executor is
     * single-threaded, so in-flight fetches can't be interrupted — instead
     * their results are dropped on arrival when stale.
     */
    private int suggestionGeneration = 0;

    private static final int COLOR_MUTED_GRAY = Color.parseColor("#6B7280");
    private static final int COLOR_DEEP_GREEN = Color.parseColor("#1B4332");

    public FoodAutoCompleteManager(Context context, SuggestionProvider suggestionProvider) {
        this.context = context;
        this.suggestionProvider = suggestionProvider;
    }

    public void setOnSuggestionSelected(OnSuggestionSelected listener) {
        this.suggestionSelectedListener = listener;
    }

    public void setOnRecentSearchSelected(OnRecentSearchSelected listener) {
        this.recentSearchListener = listener;
    }

    public void attachToEditText(EditText editText) {
        // 1. Initialize Adapter and ListPopupWindow tied to the EditText view
        adapter = new ArrayAdapter<>(context, R.layout.item_suggestion, new ArrayList<>());
        
        popupWindow = new ListPopupWindow(context);
        popupWindow.setAdapter(adapter);
        popupWindow.setAnchorView(editText);
        popupWindow.setSoftInputMode(ListPopupWindow.INPUT_METHOD_NEEDED);

        // 2. Selection Event: User taps a suggestion
        popupWindow.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            Suggestion selectedItem = adapter.getItem(position);
            String selectedLabel = selectedItem == null ? "" : selectedItem.label;
            AnalyticsTracker.suggestionTapped(
                    position, suggestionProvider.isUnbrandedSuggestion(selectedLabel));
            if (suggestionSelectedListener != null && selectedItem != null) {
                suggestionSelectedListener.onSelected(selectedItem);
            }

            // Enable suppression guard before updating text programmatically
            isSuppressingSuggestions = true;

            cancelPendingSearch();

            // Set text into EditText without triggering auto-complete flow
            editText.setText(selectedLabel);
            editText.setSelection(selectedLabel.length()); // Move cursor to end

            // Immediately dismiss popup and release focus/keyboard
            popupWindow.dismiss();
            hideKeyboard(editText);

            editText.clearFocus();
            if (editText.getRootView() != null) {
                View searchButton = editText.getRootView().findViewById(R.id.searchButton);
                if (searchButton != null) {
                    searchButton.requestFocus();
                }
            }
        });

        // 3. User Typing Event
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                cancelPendingSearch();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // If text was changed due to item selection, consume flag and return
                if (isSuppressingSuggestions) {
                    isSuppressingSuggestions = false;
                    return;
                }

                String query = s.toString().trim();

                if (query.isEmpty()) {
                    popupWindow.dismiss();
                    if (editText.hasFocus()) {
                        showRecentsPopup(editText);
                    }
                    return;
                }

                dismissRecentsPopup();

                if (query.length() < 2) {
                    popupWindow.dismiss();
                    return;
                }

                // Debounced API call (300ms)
                final int generation = suggestionGeneration;
                final String requestQuery = query;
                searchRunnable = () -> executor.execute(() -> {
                    try {
                        List<Suggestion> suggestions = suggestionProvider.fetchSuggestions(requestQuery);
                        handler.post(() -> renderSuggestions(suggestions, editText, requestQuery, generation));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                handler.postDelayed(searchRunnable, 300);
            }
        });

        // Dismiss popups if focus is lost
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                if (popupWindow.isShowing()) {
                    popupWindow.dismiss();
                }
                dismissRecentsPopup();
            } else if (editText.getText().toString().trim().isEmpty()) {
                showRecentsPopup(editText);
            }
        });
    }

    private static final int MAX_SUGGESTIONS = 5;

    private void renderSuggestions(List<Suggestion> suggestions, EditText editText,
                                   String requestQuery, int generation) {
        // Stale request: a newer keystroke already superseded it — drop.
        if (generation != suggestionGeneration) {
            return;
        }
        // The box moved on while the network was in flight — drop.
        if (!requestQuery.equals(editText.getText().toString().trim())) {
            return;
        }
        // Prevent popup rendering if view lost focus or selection was made
        if (isSuppressingSuggestions || !editText.hasFocus()) {
            popupWindow.dismiss();
            return;
        }

        if (suggestions != null && !suggestions.isEmpty()) {
            dismissRecentsPopup();
            List<Suggestion> capped = suggestions.size() > MAX_SUGGESTIONS
                    ? suggestions.subList(0, MAX_SUGGESTIONS)
                    : suggestions;
            adapter.clear();
            adapter.addAll(capped);
            adapter.notifyDataSetChanged();

            if (!popupWindow.isShowing()) {
                popupWindow.show();
            }
        } else {
            popupWindow.dismiss();
        }
    }

    /** Dismisses the suggestion popup, e.g. when the user presses search/enter. */
    public void dismissSuggestions() {
        cancelPendingSearch();
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
        dismissRecentsPopup();
    }

    /**
     * Shows the "your recent searches" panel: a single-line horizontally
     * scrollable strip of bubble chips anchored under the search box.
     */
    private void showRecentsPopup(EditText editText) {
        if (isSuppressingSuggestions || !editText.hasFocus()) return;
        List<String> recents = RecentSearches.get(context);
        if (recents.isEmpty()) return;
        dismissRecentsPopup();

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        panel.setPadding(pad, dp(10), pad, dp(10));
        panel.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(context);
        title.setText("your recent searches");
        title.setTextSize(12f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(COLOR_MUTED_GRAY);
        title.setPadding(0, 0, 0, dp(8));
        panel.addView(title);

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bubbles = new LinearLayout(context);
        bubbles.setOrientation(LinearLayout.HORIZONTAL);
        for (String recent : recents) {
            bubbles.addView(buildBubble(editText, recent));
        }
        scroller.addView(bubbles);
        panel.addView(scroller);

        recentsPopup = new PopupWindow(panel,
                editText.getWidth() > 0 ? editText.getWidth()
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        recentsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        recentsPopup.setOutsideTouchable(true);
        recentsPopup.setElevation(dp(8));
        recentsPopup.showAsDropDown(editText);
    }

    private TextView buildBubble(EditText editText, String query) {
        TextView bubble = new TextView(context);
        bubble.setText(query);
        bubble.setTextSize(13f);
        bubble.setTextColor(COLOR_DEEP_GREEN);
        bubble.setBackgroundResource(R.drawable.bg_bubble);
        bubble.setSingleLine(true);
        int hPad = dp(14);
        int vPad = dp(8);
        bubble.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        bubble.setLayoutParams(params);
        bubble.setClickable(true);
        bubble.setFocusable(true);
        bubble.setOnClickListener(v -> {
            isSuppressingSuggestions = true;
            editText.setText(query);
            editText.setSelection(query.length());
            dismissRecentsPopup();
            popupWindow.dismiss();
            isSuppressingSuggestions = false;
            if (recentSearchListener != null) {
                recentSearchListener.onSelected(query);
            }
        });
        return bubble;
    }

    private void dismissRecentsPopup() {
        if (recentsPopup != null && recentsPopup.isShowing()) {
            recentsPopup.dismiss();
        }
        recentsPopup = null;
    }

    private int dp(int dps) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dps * density);
    }

    private void cancelPendingSearch() {
        // Invalidate any in-flight fetch; its result is dropped on arrival.
        suggestionGeneration++;
        if (searchRunnable != null) {
            handler.removeCallbacks(searchRunnable);
            searchRunnable = null;
        }
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
