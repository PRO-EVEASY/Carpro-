import dev.local.jaecoorecorder.DvrAutomation;
public final class DvrAutomationHostTest {
 private static int checks;
 private static void expect(DvrAutomation.Result value,DvrAutomation.Decision decision,String label){if(value.decision!=decision)throw new AssertionError(label+": "+value.decision);checks++;}
 public static void main(String[] args){
  DvrAutomation a=new DvrAutomation();
  expect(a.update(true,false,true,0),DvrAutomation.Decision.NONE,"known ignition overrides screen off");
  expect(a.update(true,false,true,1999),DvrAutomation.Decision.NONE,"start debounce");
  expect(a.update(true,false,true,2000),DvrAutomation.Decision.START,"start");
  expect(a.update(true,false,true,4000),DvrAutomation.Decision.NONE,"no duplicate start");
  expect(a.update(false,true,true,5000),DvrAutomation.Decision.NONE,"known ignition off overrides screen on");
  expect(a.update(false,true,true,24999),DvrAutomation.Decision.NONE,"stop grace");
  expect(a.update(false,true,true,25000),DvrAutomation.Decision.STOP,"stop");
  expect(a.update(false,true,true,26000),DvrAutomation.Decision.NONE,"no duplicate stop");
  a.manualStart();a.manualStop();
  expect(a.update(true,true,true,27000),DvrAutomation.Decision.NONE,"manual hold");
  expect(a.update(true,true,true,50000),DvrAutomation.Decision.NONE,"manual hold survives time");
  expect(a.update(false,true,true,51000),DvrAutomation.Decision.NONE,"manual hold observes off");
  expect(a.update(true,true,true,52000),DvrAutomation.Decision.NONE,"new cycle debounced");
  expect(a.update(true,true,true,54000),DvrAutomation.Decision.START,"next off on releases hold");
  expect(a.update(null,null,true,55000),DvrAutomation.Decision.NONE,"unknown preserves recording");
  expect(a.update(false,false,false,56000),DvrAutomation.Decision.NONE,"disabled does not stop");
  DvrAutomation b=new DvrAutomation(1000);
  expect(b.update(null,true,true,0),DvrAutomation.Decision.NONE,"fallback debounce");
  DvrAutomation.Result fallback=b.update(null,true,true,2000);expect(fallback,DvrAutomation.Decision.START,"fallback start");
  if(!fallback.source.equals("ANDROID_INTERACTIVE_FALLBACK"))throw new AssertionError("fallback source missing");checks++;
  expect(b.update(null,false,true,3000),DvrAutomation.Decision.NONE,"fallback sleep");
  expect(b.update(null,false,true,4000),DvrAutomation.Decision.STOP,"configured grace");
  DvrAutomation c=new DvrAutomation();c.update(true,true,true,10000);
  expect(c.update(true,true,true,9000),DvrAutomation.Decision.NONE,"clock regression restarts debounce");
  expect(c.update(true,true,true,11000),DvrAutomation.Decision.START,"clock recovery");
  c.manualStop();c.update(false,true,true,12000);
  expect(c.update(null,true,true,14000),DvrAutomation.Decision.NONE,"source switch cannot release manual hold");
  expect(c.update(null,true,true,18000),DvrAutomation.Decision.NONE,"manual hold not time based");
  System.out.println("PASS "+checks+" automation checks");
 }
}
