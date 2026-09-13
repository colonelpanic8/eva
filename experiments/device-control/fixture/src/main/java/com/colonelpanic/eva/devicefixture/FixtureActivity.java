package com.colonelpanic.eva.devicefixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FixtureActivity extends Activity {
    private int clicks;

    @SuppressLint("SetTextI18n")
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("secure", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 140, 48, 48);
        TextView heading = new TextView(this);
        heading.setText("EVA cross-app fixture");
        heading.setTextSize(24);
        layout.addView(heading);
        EditText input = new EditText(this);
        input.setContentDescription("probe-input");
        input.setHint("Probe text");
        input.setSingleLine(true);
        layout.addView(input);
        Button button = new Button(this);
        button.setText("Increment counter");
        button.setContentDescription("probe-increment");
        layout.addView(button);
        TextView counter = new TextView(this);
        counter.setText("Count: 0");
        counter.setContentDescription("probe-counter");
        layout.addView(counter);
        button.setOnClickListener(v -> counter.setText("Count: " + ++clicks));
        setContentView(layout);
    }
}
