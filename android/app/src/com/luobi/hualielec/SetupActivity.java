package com.luobi.hualielec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首次安装引导 / 更换宿舍：校区 → 楼栋 → 房间。
 * 楼栋和房间都是「可搜索列表」选择（校区只有 4 个，保留下拉）。
 */
public class SetupActivity extends Activity {

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private android.widget.Spinner spCampus;
    private TextView tStatus;
    private Button btnPickDept, btnPickRoom, btnSave;
    private CheckBox cbManual;
    private EditText etManual;
    private TextView tTitle;

    private List<HualiApi.Dept> depts = new ArrayList<>();
    private final List<String> rooms = new ArrayList<>();
    private HualiApi.Dept selectedDept;
    private String selectedRoom = "";
    private int cBg, cText, cDim, cWarn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.load(getApplicationContext());
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            cBg = 0xFF111318; cText = 0xFFEDEDED; cDim = 0xFF9AA0A6; cWarn = 0xFFFF6B6B;
        } else {
            cBg = 0xFFF6F7F9; cText = 0xFF111111; cDim = 0xFF888888; cWarn = 0xFFCC3333;
        }
        setContentView(buildUi());
        loadDepts();
    }

    // ------------------------------------------------------------------ ui
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        tTitle = new TextView(this);
        tTitle.setText(prefs.onboarded ? "更换宿舍" : "欢迎使用宿舍电费");
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tTitle.setTextColor(cText);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tTitle);

        TextView hint = new TextView(this);
        hint.setText("选好校区、楼栋和房间，之后会自动读取余额和用电量。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(cDim);
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        root.addView(label("校区"));
        spCampus = new android.widget.Spinner(this);
        ArrayAdapter<String> ca = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, java.util.Arrays.asList(Prefs.campusNames()));
        ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCampus.setAdapter(ca);
        spCampus.setSelection(Prefs.campusIndex(prefs.campus));
        root.addView(spCampus);
        spCampus.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.campus = Prefs.campusKeys()[position];
                loadDepts();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        root.addView(label("楼栋 / 楼层"));
        btnPickDept = new Button(this);
        btnPickDept.setText("加载楼栋中…");
        btnPickDept.setEnabled(false);
        btnPickDept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeptPicker();
            }
        });
        root.addView(btnPickDept);

        root.addView(label("房间号"));
        btnPickRoom = new Button(this);
        btnPickRoom.setText("先选楼栋");
        btnPickRoom.setEnabled(false);
        btnPickRoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRoomPicker();
            }
        });
        root.addView(btnPickRoom);

        cbManual = new CheckBox(this);
        cbManual.setText("手动输入房号（没有楼栋列表时用）");
        cbManual.setTextColor(cDim);
        cbManual.setPadding(0, dp(10), 0, 0);
        root.addView(cbManual);

        etManual = new EditText(this);
        etManual.setHint("例如 L9-418");
        etManual.setText(prefs.room);
        etManual.setVisibility(View.GONE);
        root.addView(etManual);

        cbManual.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                etManual.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                btnPickRoom.setEnabled(!isChecked && !rooms.isEmpty());
                btnPickDept.setEnabled(!isChecked && !depts.isEmpty());
                updateSaveState();
            }
        });

        btnSave = new Button(this);
        btnSave.setText("保存并开始使用");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        root.addView(btnSave, lp);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        tStatus = new TextView(this);
        tStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tStatus.setTextColor(cDim);
        tStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tStatus);
        return scroll;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(cDim);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void updateSaveState() {
        String room = cbManual.isChecked()
                ? etManual.getText().toString().trim().toUpperCase(Locale.US)
                : selectedRoom;
        btnSave.setEnabled(room != null && !room.isEmpty());
    }

    // ------------------------------------------------------------- 可搜索列表
    private void showSearchList(String title, List<String> items, OnPick onPick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = new EditText(this);
        search.setHint("搜索…");
        search.setSingleLine(true);
        box.addView(search);

        final List<String> filtered = new ArrayList<>(items);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, filtered);
        final ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        list.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title + "（共 " + items.size() + " 项）")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim().toUpperCase(Locale.US);
                filtered.clear();
                for (String it : items) {
                    if (q.isEmpty() || it.toUpperCase(Locale.US).contains(q)) {
                        filtered.add(it);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onPick.onPick(items.indexOf(filtered.get(position)));
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private interface OnPick {
        void onPick(int index);
    }

    private void showDeptPicker() {
        if (depts.isEmpty()) {
            Toast.makeText(this, "楼栋列表还没加载好", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (HualiApi.Dept d : depts) {
            labels.add(d.label);
        }
        showSearchList("选择楼栋 / 楼层", labels, new OnPick() {
            @Override
            public void onPick(int index) {
                selectDept(depts.get(index));
            }
        });
    }

    private void showRoomPicker() {
        if (rooms.isEmpty()) {
            Toast.makeText(this, "房间列表还没加载好", Toast.LENGTH_SHORT).show();
            return;
        }
        showSearchList("选择房间", new ArrayList<>(rooms), new OnPick() {
            @Override
            public void onPick(int index) {
                selectedRoom = rooms.get(index);
                btnPickRoom.setText(selectedRoom);
                updateSaveState();
            }
        });
    }

    private void selectDept(HualiApi.Dept dept) {
        selectedDept = dept;
        btnPickDept.setText(dept.label);
        loadRooms(dept.id);
    }

    // ------------------------------------------------------------------ data
    private void loadDepts() {
        btnPickDept.setEnabled(false);
        btnPickDept.setText("加载楼栋中…");
        tStatus.setTextColor(cDim);
        tStatus.setText("正在拉取楼栋列表…");
        final Prefs p = prefs;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                List<HualiApi.Dept> list = null;
                String err = null;
                try {
                    list = HualiApi.fetchDepts(p);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<HualiApi.Dept> result = list;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && !result.isEmpty()) {
                            depts = result;
                            btnPickDept.setEnabled(true);
                            // 默认选第一个宿舍/公寓
                            HualiApi.Dept pick = result.get(0);
                            for (HualiApi.Dept d : result) {
                                if (d.label.contains("宿舍") || d.label.contains("公寓")) {
                                    pick = d;
                                    break;
                                }
                            }
                            selectDept(pick);
                            tStatus.setText("共 " + result.size() + " 个位置，点上面按钮可搜索。");
                        } else {
                            depts = new ArrayList<>();
                            btnPickDept.setText("楼栋列表拉取失败");
                            btnPickDept.setEnabled(false);
                            tStatus.setTextColor(cWarn);
                            tStatus.setText("拉取失败（" + error + "），可勾选「手动输入房号」。");
                        }
                        updateSaveState();
                    }
                });
            }
        });
    }

    private void loadRooms(final String deptId) {
        rooms.clear();
        selectedRoom = "";
        btnPickRoom.setText("加载房间中…");
        btnPickRoom.setEnabled(false);
        updateSaveState();
        final Prefs p = prefs;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                List<String> list = null;
                String err = null;
                try {
                    list = HualiApi.fetchRooms(p, deptId);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<String> result = list;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        rooms.clear();
                        if (result != null && !result.isEmpty()) {
                            rooms.addAll(result);
                            int select = 0;
                            for (int i = 0; i < rooms.size(); i++) {
                                if (rooms.get(i).equalsIgnoreCase(prefs.room)) {
                                    select = i;
                                    break;
                                }
                            }
                            selectedRoom = rooms.get(select);
                            btnPickRoom.setText(selectedRoom);
                            btnPickRoom.setEnabled(true);
                            tStatus.setTextColor(cDim);
                            tStatus.setText("该位置共 " + rooms.size() + " 间房，点上面按钮可搜索。");
                        } else {
                            btnPickRoom.setText("该位置没有房间");
                            btnPickRoom.setEnabled(false);
                            tStatus.setTextColor(cWarn);
                            tStatus.setText("房间列表拉取失败（" + error + "），可勾选「手动输入房号」。");
                        }
                        updateSaveState();
                    }
                });
            }
        });
    }

    private void save() {
        String room;
        if (cbManual.isChecked()) {
            room = etManual.getText().toString().trim().toUpperCase(Locale.US);
        } else {
            room = selectedRoom;
        }
        if (room == null || room.isEmpty()) {
            Toast.makeText(this, "请先选择或填写房号", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.room = room;
        prefs.openid = HualiApi.generateOpenid();
        prefs.onboarded = true;
        prefs.save(getApplicationContext());
        Toast.makeText(this, "已保存：" + prefs.describe(), Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
