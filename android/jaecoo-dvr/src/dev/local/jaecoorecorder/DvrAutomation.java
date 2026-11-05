package dev.local.jaecoorecorder;

/** Pure monotonic-clock policy. It never sends a vehicle command. */
public final class DvrAutomation {
 public static final long START_DEBOUNCE_MS=2000L, DEFAULT_STOP_GRACE_MS=20000L;
 public enum Decision { START, STOP, NONE }
 public static final class Result {
  public final Decision decision;
  public final String autoState,source;
  public final long remainingMs;
  public final Boolean effectivePower;
  Result(Decision d,String state,String s,long remaining,Boolean power){decision=d;autoState=state;source=s;remainingMs=remaining;effectivePower=power;}
 }
 private long stopGraceMs;
 private boolean recording,manualHold,holdSawOff,previousEnabled;
 private String holdOffSource="",previousSource="";
 private Boolean previousPower;
 private long stateSinceMs=-1,lastNowMs=-1;
 public DvrAutomation(){this(DEFAULT_STOP_GRACE_MS);}
 public DvrAutomation(long graceMs){if(graceMs<0)throw new IllegalArgumentException("stopGraceMs must be >= 0");stopGraceMs=graceMs;}
 public synchronized void setStopGraceMs(long value){if(value<0)throw new IllegalArgumentException("Negative grace");stopGraceMs=value;}
 public synchronized void setRecording(boolean value){recording=value;}
 public synchronized boolean isRecording(){return recording;}
 /** Call for an explicit user Stop, not for an automatic stop or recording failure. */
 public synchronized void manualStop(){recording=false;manualHold=true;holdSawOff=false;holdOffSource="";}
 /** A later explicit user Start may override the manual-stop hold. */
 public synchronized void manualStart(){manualHold=false;holdSawOff=false;holdOffSource="";recording=true;}
 public synchronized Result update(Boolean ignition,Boolean interactive,boolean enabled,long monotonicNowMs){
  long now=Math.max(0,monotonicNowMs);
  Boolean power=ignition!=null?ignition:interactive;
  String source=ignition!=null?"IGNITION_STATE":interactive!=null?"ANDROID_INTERACTIVE_FALLBACK":"UNKNOWN";
  if(lastNowMs>=0&&now<lastNowMs){stateSinceMs=now;previousPower=null;previousSource="";}
  lastNowMs=now;
  if(enabled!=previousEnabled){stateSinceMs=now;previousEnabled=enabled;}
  if(!equal(power,previousPower)||!source.equals(previousSource)||stateSinceMs<0){stateSinceMs=now;previousPower=power;previousSource=source;}
  if(manualHold&&power!=null){
   if(!power){holdSawOff=true;holdOffSource=source;}
   else if(holdSawOff&&source.equals(holdOffSource)){manualHold=false;holdSawOff=false;holdOffSource="";stateSinceMs=now;}
  }
  if(!enabled)return result(Decision.NONE,"הקלטה אוטומטית כבויה",source,0,power);
  if(manualHold)return result(Decision.NONE,"נעצר ידנית; ממתין לכיבוי/הפעלה הבאים",source,0,power);
  if(power==null)return result(Decision.NONE,"אין מידע על חשמל; ההקלטה הנוכחית נשמרת",source,0,null);
  long elapsed=Math.max(0,now-stateSinceMs);
  String suffix=ignition==null?" (אות מסך/שינה של Android)":" (אות הצתה)";
  if(power){
   if(recording)return result(Decision.NONE,"ההקלטה פעילה"+suffix,source,0,power);
   long remaining=Math.max(0,START_DEBOUNCE_MS-elapsed);
   if(remaining>0)return result(Decision.NONE,"ממתין להפעלה אוטומטית"+suffix,source,remaining,power);
   recording=true;return result(Decision.START,"הקלטה אוטומטית מתחילה"+suffix,source,0,power);
  }
  if(!recording)return result(Decision.NONE,"החשמל כבוי; ההקלטה ממתינה"+suffix,source,0,power);
  long remaining=Math.max(0,stopGraceMs-elapsed);
  if(remaining>0)return result(Decision.NONE,"ממתין לזמן הכיבוי"+suffix,source,remaining,power);
  recording=false;return result(Decision.STOP,"הקלטה אוטומטית נעצרת"+suffix,source,0,power);
 }
 private static boolean equal(Boolean a,Boolean b){return a==b||(a!=null&&a.equals(b));}
 private static Result result(Decision d,String state,String source,long remaining,Boolean power){return new Result(d,state,source,remaining,power);}
}
