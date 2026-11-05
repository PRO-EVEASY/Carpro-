package dev.local.sysinfo;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
 private static final int BG=0xff07080b,WHITE=0xfff6f1e7,MUTED=0xff9ca3ae,GOLD=0xffd6b274,CARD=0xff12141a;
 private TextView output;

 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  LinearLayout root=new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setPadding(dp(18),dp(14),dp(18),dp(14));
  root.setBackgroundColor(BG);
  setContentView(root);

  TextView title=text("מידע מערכת",26,GOLD);
  title.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
  root.addView(title);
  root.addView(text("קריאה בלבד. מה שהמערכת מדווחת על עצמה, כולל דגם, ליבה, SoC ומאפיינים.",12,MUTED));
  space(root,12);

  Button show=button("▶  הצג מידע מערכת",v->show());
  show.setTextColor(BG);
  show.setBackgroundColor(GOLD);
  root.addView(show,new LinearLayout.LayoutParams(-1,dp(52)));
  space(root,12);

  output=new TextView(this);
  output.setTypeface(Typeface.MONOSPACE);
  output.setTextSize(12);
  output.setTextColor(WHITE);
  output.setTextIsSelectable(true);
  output.setPadding(dp(14),dp(12),dp(14),dp(12));
  output.setBackgroundColor(CARD);
  output.setText("לחץ 'הצג מידע מערכת'. הדוח המלא נשמר ב: Android/data/dev.local.sysinfo/files/logs/system-info.txt");
  ScrollView scroll=new ScrollView(this);
  scroll.addView(output);
  root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
 }

 private void show(){output.setText(SysInfo.report(this));}

 private TextView text(String value,float size,int color){
  TextView v=new TextView(this);
  v.setText(value);
  v.setTextSize(size);
  v.setTextColor(color);
  return v;
 }

 private Button button(String title,View.OnClickListener click){
  Button b=new Button(this);
  b.setText(title);
  b.setAllCaps(false);
  b.setTextSize(14);
  b.setTextColor(WHITE);
  b.setBackgroundColor(CARD);
  b.setOnClickListener(click);
  return b;
 }

 private void space(LinearLayout parent,int height){
  parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
 }

 private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
}
