package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WindowsDeploymentCustodyTest {
  static byte[] descriptor(byte[] sid) {
    int acl = 20 + sid.length;
    byte[] raw = new byte[acl + 16 + sid.length];
    var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    raw[0] = 1; b.putShort(2,(short)0x9004); b.putInt(4,20); b.putInt(16,acl);
    System.arraycopy(sid,0,raw,20,sid.length); raw[acl]=2;
    b.putShort(acl+2,(short)(16+sid.length)); b.putShort(acl+4,(short)1);
    b.putShort(acl+10,(short)(8+sid.length)); b.putInt(acl+12,0x120089);
    System.arraycopy(sid,0,raw,acl+16,sid.length); return raw;
  }
  static class Api implements WindowsDeploymentCustody.Api {
    final Map<Object,String> paths = new IdentityHashMap<>();
    final Map<Object,Integer> offsets = new IdentityHashMap<>();
    Object pointer;
    int frees, extracts, extents, steps;
    public Object allocate(int bytes) { return new byte[bytes]; }
    public Object open(String path,boolean directory) { Object h=new Object(); paths.put(h,path); offsets.put(h,0); return h; }
    public WindowsDeploymentCustody.Identity identity(Object h,Object q,Runnable check) {
      steps++; check.run(); String p=paths.get(h);
      return new WindowsDeploymentCustody.Identity(1,p.equals("C:\\root")?2:p.equals("C:\\")?1:10L+p.hashCode(),
          p.equals("C:\\")||p.equals("C:\\root"),false,false,1,0);
    }
    public String mapping(String root,Object b,Runnable check) { check.run(); return "\\Device\\HarddiskVolume1"; }
    public String finalPath(Object h,Object b,Runnable check) { check.run(); return "\\\\?\\"+paths.get(h); }
    public boolean queryDescriptor(Object h,Object storage) { pointer=descriptor(DeploymentCredentialLoaderTest.policy().owner(-1)); return true; }
    public Object descriptorPointer(Object storage) { extracts++; return pointer; }
    public long extent(Object d) { extents++; return ((byte[])d).length; }
    public byte[] descriptorBytes(Object d,int n) { return ((byte[])d).clone(); }
    public int read(Object h,byte[] out,int off,int length,Object memory,Runnable check) {
      check.run(); String p=paths.get(h); byte[] bytes=p.endsWith("manifest")?DeploymentCredentialLoaderTest.manifest():p.endsWith("key")?new byte[32]:new byte[]{1};
      int used=offsets.get(h); if(used==bytes.length)return -1;
      int count=Math.min(3,Math.min(length,bytes.length-used)); System.arraycopy(bytes,used,out,off,count); offsets.put(h,used+count); return count;
    }
    public void clear(Object m) { java.util.Arrays.fill((byte[])m,(byte)0); }
    public void release(DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind kind,Object value) { frees++; }
  }
  static void load(Api api,DeploymentCredentialLoader.CustodyAttemptReceipt receipt,DeploymentCredentialLoader.HandoffSlot slot) {
    new DeploymentCredentialLoader(new WindowsDeploymentCustody(()->api)).load(DeploymentCredentialLoaderTest.policy(),receipt,slot);
  }
  @Test void normalWholeOperationsEndBeforeReleaseAndRealManifestHandoff() {
    Api api=new Api(); new WindowsDeploymentCustody(); assertEquals(0,api.frees);
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt(); var slot=new DeploymentCredentialLoader.HandoffSlot();
    load(api,receipt,slot); assertEquals("RELEASED",receipt.snapshot().state()); assertEquals(0,receipt.snapshot().retained());
    var value=slot.claim(); slot.acknowledgeConstruction(); slot.close(); assertEquals(32,value.credential().copy().length); value.credential().close();
  }
  @Test void blockedAllocationAndOpenSealRetainOriginalLateObjectWithoutFree() throws Exception {
    for(boolean open:new boolean[]{false,true}) {
      var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt(); var entered=new CountDownLatch(1); var resume=new CountDownLatch(1);
      var error=new AtomicReference<Throwable>();
      Api api=new Api() {
        Object block(Object value) { entered.countDown(); try{assertTrue(resume.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return value; }
        public Object allocate(int n){return open?super.allocate(n):block(new byte[n]);}
        public Object open(String p,boolean d){return block(super.open(p,d));}
      };
      Thread thread=new Thread(()->{try{load(api,receipt,new DeploymentCredentialLoader.HandoffSlot());}catch(Throwable e){error.set(e);}});
      thread.start(); assertTrue(entered.await(2,TimeUnit.SECONDS));
      try{receipt.release(null);}finally{resume.countDown();thread.join(2000);}
      assertFalse(thread.isAlive()); assertNotNull(error.get()); assertTrue(receipt.snapshot().unknown()); assertTrue(receipt.snapshot().retained()>0); assertEquals(0,api.frees);
    }
  }
  @Test void querySealProtectsOutputStorageAndSkipsPointerExtraction() {
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();
    Api api=new Api(){public boolean queryDescriptor(Object h,Object s){pointer=new Object();receipt.release(null);return true;}};
    assertThrows(RuntimeException.class,()->load(api,receipt,new DeploymentCredentialLoader.HandoffSlot()));
    assertEquals(0,api.extracts); assertEquals(0,api.extents); assertEquals(0,api.frees); assertTrue(receipt.snapshot().unknown());
  }
  @Test void identityAndReadFirstStepSealStopsRemainingStepsAndCopies() {
    for(boolean read:new boolean[]{false,true}) {
      var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt(); int[] later={0};
      Api api=new Api(){
        public WindowsDeploymentCustody.Identity identity(Object h,Object q,Runnable check){if(!read){receipt.release(null);check.run();later[0]++;}return super.identity(h,q,check);}
        public int read(Object h,byte[] b,int o,int n,Object m,Runnable check){receipt.release(null);check.run();later[0]++;return 0;}
      };
      assertThrows(RuntimeException.class,()->load(api,receipt,new DeploymentCredentialLoader.HandoffSlot())); assertEquals(0,later[0]);
      assertTrue(receipt.snapshot().unknown());
    }
  }
  @Test void populatedThrowAndExtractionErrorPreserveFirstFatalOpaqueOwner() {
    AssertionError first=new AssertionError("query");
    Api api=new Api(){public boolean queryDescriptor(Object h,Object s){pointer=new Object();throw first;}
      public Object descriptorPointer(Object s){throw new AssertionError("extract");}};
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();
    assertSame(first,assertThrows(AssertionError.class,()->load(api,receipt,new DeploymentCredentialLoader.HandoffSlot())));
    first.setStackTrace(new StackTraceElement[0]); System.gc(); assertTrue(receipt.snapshot().unknown()); assertTrue(receipt.snapshot().retained()>0); assertEquals(0,api.extents);
  }
  @Test void normalSequentialSlotReuseAndClearFailureStillIndependentFreeOnce() {
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim(); Object op=receipt.beginOperation(); Api api=new Api();
    for(int i=0;i<3;i++){var r=receipt.reserve(op,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);receipt.bindReturned(op,r,new Object());receipt.closeOwned(op,r);}
    receipt.endOperation(op);receipt.release(null); assertEquals(3,api.frees);assertEquals(0,receipt.snapshot().retained());
    var bad=new DeploymentCredentialLoader.CustodyAttemptReceipt();bad.claim();Object token=bad.beginOperation(); AssertionError fatal=new AssertionError("clear");
    Api failing=new Api(){public void clear(Object m){throw fatal;}};
    var r=bad.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.QUERY_MEMORY,failing);bad.bindReturned(token,r,new byte[33]);bad.endOperation(token);
    assertSame(fatal,bad.release(null)); assertEquals(1,failing.frees);bad.release(null);assertEquals(1,failing.frees);assertTrue(bad.snapshot().unknown());
  }
  @Test void staleAndForeignRecordMethodsRejectWithoutChangingEitherOwner() {
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim();Object token=receipt.beginOperation();Api api=new Api();
    var stale=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);
    receipt.bindReturned(token,stale,new Object());receipt.closeOwned(token,stale);
    var fresh=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);
    assertThrows(RuntimeException.class,()->receipt.bindReturned(token,stale,new Object()));
    assertThrows(RuntimeException.class,()->receipt.finishEmptyFailure(token,stale));
    assertThrows(RuntimeException.class,()->receipt.reconcileDescriptor(token,stale,true,new Object(),false));
    assertThrows(RuntimeException.class,()->receipt.closeOwned(token,stale));
    var foreign=new DeploymentCredentialLoader.CustodyAttemptReceipt();foreign.claim();Object other=foreign.beginOperation();
    assertThrows(RuntimeException.class,()->foreign.bindReturned(other,fresh,new Object()));
    assertEquals(0,foreign.snapshot().retained());assertFalse(foreign.snapshot().unknown());
    receipt.bindReturned(token,fresh,new Object());receipt.closeOwned(token,fresh);receipt.endOperation(token);receipt.release(null);
    assertEquals(0,receipt.snapshot().retained());assertEquals(2,api.frees);
  }
  @Test void actualNativeCompositeChecksBeforeLaterCallsOrBoundedMemoryReads() {
    for(int stop:new int[]{1,2,3}) {
      int[] calls={0},reads={0}; boolean[] invalid={false};
      WindowsDeploymentCustody.NativeSurface surface=new WindowsDeploymentCustody.NativeSurface(){
        int step(){if(++calls[0]==stop)invalid[0]=true;return 1;}
        public int fileType(Object h){step();return 1;}
        public boolean information(Object h,Object q){step();return true;}
        public boolean standard(Object h,Object q){step();return true;}
        public boolean volume(Object h,Object q){step();return true;}
        public boolean read(Object h,Object q,int n){step();return true;}
      };
      WindowsDeploymentCustody.BoundedBuffer memory=new WindowsDeploymentCustody.BoundedBuffer(){
        public void clear(){}
        public int integer(int offset){reads[0]++;return 1;}
        public byte octet(int offset){reads[0]++;return 0;}
        public short word(int offset){reads[0]++;return 0;}
        public void copy(byte[] b,int o,int n){reads[0]++;}
      };
      var actual=new WindowsDeploymentCustody.NativeApi(surface);
      Runnable check=()->{if(invalid[0])throw new DeploymentCredentialLoader.Unavailable();};
      assertThrows(RuntimeException.class,()->actual.identity(new Object(),memory,check));
      assertEquals(stop,calls[0]);if(stop<3)assertEquals(0,reads[0]);
      calls[0]=0;reads[0]=0;invalid[0]=false;
      assertThrows(RuntimeException.class,()->actual.read(new Object(),new byte[33],0,33,memory,()->{throw new DeploymentCredentialLoader.Unavailable();}));
      assertEquals(1,calls[0]);assertEquals(0,reads[0]);
    }
  }
  @Test void unknownReceiptRetainsLateResourceAfterLocalReferenceAndThrowableCleared() {
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim();Object token=receipt.beginOperation();Api api=new Api();
    var record=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE,api);
    Object value=new Object();var weak=new java.lang.ref.WeakReference<>(value);receipt.release(null);
    try{receipt.bindReturned(token,record,value);}catch(RuntimeException expected){expected.setStackTrace(new StackTraceElement[0]);}
    value=null;record=null;System.gc();assertNotNull(weak.get());assertEquals(1,receipt.snapshot().retained());assertEquals(0,api.frees);
  }
  @Test void actualLoaderRejectsRealManifestPurposeAndContextMutations() {
    for(int mode=0;mode<3;mode++) {
      int selected=mode;
      Api api=new Api(){
        public int read(Object h,byte[] b,int o,int n,Object m,Runnable check){
          String path=paths.get(h);
          if(selected==0&&path.endsWith("manifest")){
            byte[] raw=new String(DeploymentCredentialLoaderTest.manifest(),java.nio.charset.StandardCharsets.US_ASCII)
                .replace("SERVER_AUTHORITY","CLIENT_CONTROLLER").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int used=offsets.get(h);if(used==raw.length)return -1;int count=Math.min(n,raw.length-used);
            System.arraycopy(raw,used,b,o,count);offsets.put(h,used+count);return count;
          }
          int count=super.read(h,b,o,n,m,check);
          if(selected>0&&count>0&&path.endsWith(selected==1?"mod":"binding"))b[o]^=1;
          return count;
        }
      };
      var slot=new DeploymentCredentialLoader.HandoffSlot();var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();
      assertThrows(RuntimeException.class,()->load(api,receipt,slot));assertEquals("EMPTY",slot.state());assertEquals("RELEASED",receipt.snapshot().state());
    }
  }
  @Test void credentialReaderGrantOutsideExplicitSetRejectsActualDescriptorParser() {
    var policy=DeploymentCredentialLoaderTest.policy();byte[] sid=policy.owner(-1);byte[] raw=descriptor(sid);
    int acl=20+sid.length;raw[acl+16+sid.length-4]=19;
    assertThrows(RuntimeException.class,()->WindowsDeploymentCustody.assess(raw,policy,false,4));
    WindowsDeploymentCustody.assess(raw,policy,false,0); // Public artifact read is not credential confidentiality.
  }
  @Test void actualNativeMappingAndPathStopBeforeBufferConsumptionAtEachNativeBoundary() {
    for(int stop:new int[]{1,2}) {
      int[] calls={0}, reads={0};boolean[] sealed={false};
      WindowsDeploymentCustody.NativeSurface surface=new WindowsDeploymentCustody.NativeSurface(){
        int step(){if(++calls[0]==stop)sealed[0]=true;return 24;}
        public int fileType(Object h){return 1;}
        public boolean information(Object h,Object b){return true;}
        public boolean standard(Object h,Object b){return true;}
        public boolean volume(Object h,Object b){return true;}
        public boolean read(Object h,Object b,int n){return true;}
        public int mapping(String d,Object b,int n){return step();}
        public int driveType(String r){step();return 3;}
        public int finalPath(Object h,Object b,int n){step();return 12;}
      };
      WindowsDeploymentCustody.BoundedBuffer memory=new WindowsDeploymentCustody.BoundedBuffer(){
        public void clear(){}
        public int integer(int o){reads[0]++;return 0;}
        public byte octet(int o){reads[0]++;return 0;}
        public short word(int o){reads[0]++;return 0;}
        public void copy(byte[] b,int o,int n){reads[0]++;}
      };
      var actual=new WindowsDeploymentCustody.NativeApi(surface);
      Runnable check=()->{if(sealed[0])throw new DeploymentCredentialLoader.Unavailable();};
      assertThrows(RuntimeException.class,()->actual.mapping("C:\\",memory,check));
      assertEquals(stop,calls[0]);assertEquals(0,reads[0]);
      calls[0]=0;sealed[0]=false;
      assertThrows(RuntimeException.class,()->actual.finalPath(new Object(),memory,()->{throw new DeploymentCredentialLoader.Unavailable();}));
      assertEquals(1,calls[0]);assertEquals(0,reads[0]);
    }
  }
  @Test void recursiveValidateAndReadCaughtByPortStillSealOuterOperation() {
    for(boolean read:new boolean[]{false,true}) {
      var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim();
      DeploymentCredentialLoader.Custody[] captured={null};
      Api api=new Api(){
        public WindowsDeploymentCustody.Identity identity(Object h,Object q,Runnable check){
          if(captured[0]!=null){assertThrows(RuntimeException.class,()->captured[0].validate());check.run();}
          return super.identity(h,q,check);
        }
        public int read(Object h,byte[] b,int o,int n,Object m,Runnable check){
          assertThrows(RuntimeException.class,()->captured[0].file(DeploymentCredentialLoader.Purpose.CREDENTIAL).read(b,o,n));
          check.run();return 0;
        }
      };
      captured[0]=new WindowsDeploymentCustody(()->api).acquire(DeploymentCredentialLoaderTest.policy(),receipt);
      if(read)assertThrows(RuntimeException.class,()->captured[0].file(DeploymentCredentialLoader.Purpose.CREDENTIAL).read(new byte[33],0,33));
      else assertThrows(RuntimeException.class,()->captured[0].validate());
      receipt.release(null);assertTrue(receipt.snapshot().unknown());assertTrue(receipt.snapshot().retained()>0);
    }
  }
  @Test void actualNativeDescriptorSurfaceSealedQuerySkipsExtractionExtentAndCopy() {
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();int[] steps={0,0,0,0};
    WindowsDeploymentCustody.NativeSurface surface=new WindowsDeploymentCustody.NativeSurface(){
      public int fileType(Object h){return 1;}
      public boolean information(Object h,Object b){return true;}
      public boolean standard(Object h,Object b){return true;}
      public boolean volume(Object h,Object b){return true;}
      public boolean read(Object h,Object b,int n){return true;}
      public boolean descriptor(Object h,Object b){steps[0]++;receipt.release(null);return true;}
      public Object pointer(Object b){steps[1]++;return new Object();}
      public long extent(Object p){steps[2]++;return 64;}
      public byte[] bytes(Object p,int n){steps[3]++;return new byte[n];}
    };
    var actual=new WindowsDeploymentCustody.NativeApi(surface);
    class Buffer implements WindowsDeploymentCustody.BoundedBuffer {
      public void clear(){} public int integer(int o){return 0;}public byte octet(int o){return 0;}
      public short word(int o){return 0;}public void copy(byte[] b,int o,int n){}
    }
    Api api=new Api(){
      public Object allocate(int n){return new Buffer();}
      public boolean queryDescriptor(Object h,Object b){return actual.queryDescriptor(h,b);}
      public Object descriptorPointer(Object b){return actual.descriptorPointer(b);}
      public long extent(Object p){return actual.extent(p);}
      public byte[] descriptorBytes(Object p,int n){return actual.descriptorBytes(p,n);}
      public void clear(Object b){}
    };
    assertThrows(RuntimeException.class,()->load(api,receipt,new DeploymentCredentialLoader.HandoffSlot()));
    assertArrayEquals(new int[]{1,0,0,0},steps);assertEquals(0,api.frees);assertTrue(receipt.snapshot().unknown());
  }
}
