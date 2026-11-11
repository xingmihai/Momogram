/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/** Manage VLESS profiles: add via vless:// link, enable, export, delete. */
public class VlessProxyActivity extends BaseFragment {

    private LinearLayout container;
    private TextView statusView;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.VlessTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        fragmentView = scroll;
        refresh(context);
        return fragmentView;
    }

    private void refresh(Context context) {
        if (container == null) return;
        container.removeAllViews();
        VlessBridge.ensureStarted(context);

        statusView = new TextView(context);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        statusView.setTextSize(15);
        statusView.setPadding(dp(16), dp(18), dp(16), dp(10));
        VlessProfile active = VlessStore.active(context);
        String unsupported = active == null ? null : active.unsupportedReason();
        if (VlessBridge.isRunning()) {
            statusView.setText(LocaleController.getString(R.string.VlessStatusRunning)
                    + "\n127.0.0.1:" + VlessBridge.getPort()
                    + (active == null ? "" : "\n" + active.displayName()));
        } else if (active != null && unsupported != null) {
            statusView.setText(LocaleController.getString(R.string.VlessStatusStopped)
                    + "\n" + unsupported);
        } else {
            statusView.setText(LocaleController.getString(R.string.VlessStatusStopped));
        }
        container.addView(statusView);

        container.addView(makeButton(context, LocaleController.getString(R.string.VlessAdd), v -> showAddDialog(context)));
        if (active != null) {
            container.addView(makeButton(context, LocaleController.getString(R.string.VlessDisable),
                    v -> {
                        VlessBridge.disable(context);
                        toast(context, LocaleController.getString(R.string.VlessDisabledOk));
                        refresh(context);
                    }));
        }

        container.addView(makeHeader(context, LocaleController.getString(R.string.VlessSavedList)));

        List<VlessProfile> profiles = VlessStore.list(context);
        if (profiles.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.VlessEmpty));
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            container.addView(empty);
        } else {
            for (VlessProfile p : profiles) {
                container.addView(makeProfileRow(context, p));
            }
        }

        TextView about = new TextView(context);
        about.setText(LocaleController.getString(R.string.VlessAbout));
        about.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        about.setTextSize(12);
        about.setPadding(dp(16), dp(20), dp(16), dp(24));
        about.setAlpha(0.7f);
        container.addView(about);
    }

    private TextView makeHeader(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text.toUpperCase());
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setAlpha(0.65f);
        tv.setPadding(dp(16), dp(22), dp(16), dp(8));
        return tv;
    }

    private Button makeButton(Context context, String text, View.OnClickListener listener) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        b.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView makeProfileRow(Context context, VlessProfile p) {
        TextView tv = new TextView(context);
        String mark = p.id.equals(VlessStore.activeId(context)) ? "● " : "○ ";
        tv.setText(mark + p.displayName() + "\n" + p.describe());
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setTextSize(14);
        tv.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(1);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> showProfileActions(context, p));
        return tv;
    }

    private void showAddDialog(Context context) {
        final EditText input = new EditText(context);
        input.setHint(LocaleController.getString(R.string.VlessAddHint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.VlessAdd))
                .setView(input)
                .setNegativeButton(LocaleController.getString(R.string.VlessCancel), null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String link = input.getText().toString().trim();
                    try {
                        VlessProfile p = VlessProfile.parse(link);
                        String invalid = p.validate();
                        if (invalid != null) {
                            toast(context, invalid);
                            return;
                        }
                        VlessStore.add(context, p);
                        toast(context, LocaleController.getString(R.string.VlessAdded));
                        refresh(context);
                        String reason = p.unsupportedReason();
                        if (reason != null) {
                            new AlertDialog.Builder(context)
                                    .setTitle(LocaleController.getString(R.string.VlessUnsupportedTitle))
                                    .setMessage(reason)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }
                    } catch (Exception e) {
                        toast(context, LocaleController.getString(R.string.VlessInvalid) + ": " + e.getMessage());
                    }
                })
                .show();
    }

    private void showProfileActions(Context context, VlessProfile p) {
        final CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.VlessEnable),
                LocaleController.getString(R.string.VlessCopyLink),
                LocaleController.getString(R.string.VlessExportConfig),
                LocaleController.getString(R.string.VlessShare),
                LocaleController.getString(R.string.VlessDelete)
        };
        new AlertDialog.Builder(context)
                .setTitle(p.displayName())
                .setItems(items, (DialogInterface dialog, int which) -> {
                    switch (which) {
                        case 0:
                            if (VlessBridge.enable(context, p)) {
                                toast(context, LocaleController.getString(R.string.VlessEnabledOk));
                            } else {
                                String err = VlessBridge.getLastError();
                                new AlertDialog.Builder(context)
                                        .setTitle(LocaleController.getString(R.string.VlessUnsupportedTitle))
                                        .setMessage(TextUtils.isEmpty(err) ? "unknown error" : err)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                            }
                            refresh(context);
                            break;
                        case 1:
                            AndroidUtilities.addToClipboard(VlessExport.link(p));
                            toast(context, LocaleController.getString(R.string.VlessCopied));
                            break;
                        case 2: {
                            String config = VlessExport.singBox(p);
                            AndroidUtilities.addToClipboard(config);
                            new AlertDialog.Builder(context)
                                    .setTitle(LocaleController.getString(R.string.VlessExportConfig))
                                    .setMessage(config)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                            toast(context, LocaleController.getString(R.string.VlessCopied));
                            break;
                        }
                        case 3: {
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("text/plain");
                            send.putExtra(Intent.EXTRA_TEXT, VlessExport.link(p));
                            context.startActivity(Intent.createChooser(send, p.displayName()));
                            break;
                        }
                        default:
                            new AlertDialog.Builder(context)
                                    .setMessage(LocaleController.getString(R.string.VlessConfirmDelete))
                                    .setNegativeButton(LocaleController.getString(R.string.VlessCancel), null)
                                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                                        if (p.id.equals(VlessStore.activeId(context))) {
                                            VlessBridge.disable(context);
                                        }
                                        VlessStore.remove(context, p.id);
                                        toast(context, LocaleController.getString(R.string.VlessDeleted));
                                        refresh(context);
                                    })
                                    .show();
                            break;
                    }
                })
                .show();
    }

    /** Entry point used when a vless:// link is scanned, tapped or pasted elsewhere. */
    public static boolean importLink(final Activity ctx, final String link) {
        try {
            final VlessProfile p = VlessProfile.parse(link);
            String invalid = p.validate();
            if (invalid != null) {
                toast(ctx, invalid);
                return false;
            }
            VlessStore.add(ctx, p);
            final String reason = p.unsupportedReason();
            StringBuilder message = new StringBuilder(p.displayName()).append('\n').append(p.describe());
            if (reason != null) {
                message.append("\n\n").append(reason);
            }
            new AlertDialog.Builder(ctx)
                    .setTitle(LocaleController.getString(R.string.VlessAdd))
                    .setMessage(message.toString())
                    .setNegativeButton(LocaleController.getString(R.string.VlessCancel), null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (reason != null) {
                            // Store the profile and hand over a ready-to-use config instead.
                            AndroidUtilities.addToClipboard(VlessExport.singBox(p));
                            toast(ctx, LocaleController.getString(R.string.VlessCopied));
                        } else if (VlessBridge.enable(ctx, p)) {
                            toast(ctx, LocaleController.getString(R.string.VlessEnabledOk));
                        } else {
                            String err = VlessBridge.getLastError();
                            toast(ctx, TextUtils.isEmpty(err) ? "unknown error" : err);
                        }
                    })
                    .show();
            return true;
        } catch (Exception e) {
            toast(ctx, LocaleController.getString(R.string.VlessInvalid) + ": " + e.getMessage());
            return false;
        }
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
