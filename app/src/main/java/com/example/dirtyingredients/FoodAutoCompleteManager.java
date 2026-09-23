package com.example.dirtyingredients;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListPopupWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FoodAutoCompleteManager {

    private final Context context;
    private final SuggestionProvider suggestionProvider;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ListPopupWindow popupWindow;
    private ArrayAdapter<String> adapter;
    private Runnable searchRunnable;

    // Guard flag to suppress auto-complete triggers during programmatic edits/selections
    private boolean isSuppressingSuggestions = false;

    public FoodAutoCompleteManager(Context context, SuggestionProvider suggestionProvider) {
        this.context = context;
        this.suggestionProvider = suggestionProvider;
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
            String selectedItem = adapter.getItem(position);
            AnalyticsTracker.suggestionTapped(
                    position, suggestionProvider.isUnbrandedSuggestion(selectedItem));
            
            // Enable suppression guard before updating text programmatically
            isSuppressingSuggestions = true;
            
            cancelPendingSearch();

            // Set text into EditText without triggering auto-complete flow
            editText.setText(selectedItem);
            if (selectedItem != null) {
                editText.setSelection(selectedItem.length()); // Move cursor to end
            }

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

                if (query.length() < 2) {
                    popupWindow.dismiss();
                    return;
                }

                // Debounced API call (300ms)
                searchRunnable = () -> executor.execute(() -> {
                    try {
                        List<String> suggestions = suggestionProvider.fetchSuggestions(query);
                        handler.post(() -> renderSuggestions(suggestions, editText));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                handler.postDelayed(searchRunnable, 300);
            }
        });

        // Dismiss popup if focus is lost
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        });
    }

    private static final int MAX_SUGGESTIONS = 5;

    private void renderSuggestions(List<String> suggestions, EditText editText) {
        // Prevent popup rendering if view lost focus or selection was made
        if (isSuppressingSuggestions || !editText.hasFocus()) {
            popupWindow.dismiss();
            return;
        }

        if (suggestions != null && !suggestions.isEmpty()) {
            List<String> capped = suggestions.size() > MAX_SUGGESTIONS
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
    }

    private void cancelPendingSearch() {
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
