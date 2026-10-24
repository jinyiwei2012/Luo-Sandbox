package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Mechanisms only: never deployment-custody or dedicated-server validation. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class WindowsDeploymentMechanismNativeTest {
  private static Session session; // Pure Java, bounded selected-class lifetime; no native initializer.
  private static final class Session {
    final DeploymentCredentialLoader.CustodyAttemptReceipt[] receipts = new DeploymentCredentialLoader.CustodyAttemptReceipt[3];
    final Path[] directories = new Path[3];
    boolean poisoned;
  }
  private static final class Attempt {
    final int index;
    final String nonce;
    final Path directory, file;
    final DeploymentCredentialLoader.CustodyAttemptReceipt receipt;
    final WindowsDeploymentCustody.AssessmentProfile profile;
    Object operation;
    Attempt(int index) throws Exception {
      Assumptions.assumeTrue("1".equals(System.getenv("RUN_JAVA_CUSTODY_MECHANISM_NATIVE")), "explicit native mechanism opt-in required");
      assertTrue(System.getProperty("os.name").startsWith("Windows"));
      nonce = required("JAVA_CUSTODY_RUN_NONCE"); assertTrue(nonce.matches("[0-9a-f]{32}"));
      profile=WindowsDeploymentCustody.AssessmentProfile.valueOf(required("JAVA_CUSTODY_ASSESSMENT_PROFILE"));
      Path output = Path.of(required("JAVA_CUSTODY_EVIDENCE_DIR"));
      assertTrue(output.isAbsolute()); assertEquals(output.normalize(), output);
      assertTrue(Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS));
      Path approved = Path.of("C:\\Users\\Administrator\\AppData\\Local\\Temp\\opencode").toRealPath();
      assertTrue(output.toRealPath().startsWith(approved));
      for(Path p=output;p!=null;p=p.getParent()) assertFalse(Files.isSymbolicLink(p));
      this.index=index;
      if(session==null) session=new Session();
      if(session.poisoned) { result(index,nonce,"BLOCKED_BY_PRIOR_UNKNOWN",""); fail("BLOCKED_BY_PRIOR_UNKNOWN"); }
      directory=output.resolve("dummy-"+index); session.directories[index]=directory;
      file=directory.resolve("public-raw32.bin");
      receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();session.receipts[index]=receipt;
      try { Files.createDirectory(directory); Files.write(file,new byte[32],StandardOpenOption.CREATE_NEW); }
      catch(Throwable failure) { session.poisoned=true; throw failure; }
      receipt.claim();operation=receipt.beginOperation();
      if(index==0){try{captureIdentity(output,nonce);}catch(Throwable failure){session.poisoned=true;throw failure;}}
    }
    Object memory(WindowsDeploymentCustody.Api api,int size,boolean wchar) {
      var r=receipt.reserve(operation,wchar?DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.WCHAR_MEMORY:
          DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.QUERY_MEMORY,api);
      Object value;
      try{value=api.allocate(size);}catch(Throwable e){receipt.finishEmptyFailure(operation,r);throw e;}
      return receipt.bindReturned(operation,r,value);
    }
    Object open(WindowsDeploymentCustody.Api api) {
      var r=receipt.reserve(operation,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE,api);
      Object value;
      try{value=api.open(file.toString(),false);}catch(Throwable e){receipt.finishEmptyFailure(operation,r);throw e;}
      return receipt.bindReturned(operation,r,value);
    }
    Runnable check(){return ()->receipt.check(operation);}
    void finish(Throwable first,String status,String facts) throws Exception {
      final Throwable body=first;
      finishSteps(body,()->receipt.snapshot().unknown(),()->session.poisoned=true,
          finalStatus->result(index,nonce,finalStatus,facts),status,
          ()->{receipt.endOperation(operation);operation=null;},
          ()->{Throwable failed=receipt.release(null); if(failed!=null)raise(failed);},
          ()->{try{Files.writeString(directory.resolve("cleanup.txt"),receipt.snapshot().toString(),StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new IllegalStateException("cleanup evidence failed");}},
          ()->{if(!receipt.snapshot().unknown()){try{Files.delete(file);session.receipts[index]=null;}catch(Exception e){throw new IllegalStateException("dummy cleanup failed");}}});
    }
  }
  @Test @Order(1) void heldIdentityAndRead() throws Exception {
    Attempt a=new Attempt(0);Throwable failure=null;String facts="";
    try{
      var api=new WindowsDeploymentCustody.NativeApi();Object query=a.memory(api,4096,false), names=a.memory(api,520,true), read=a.memory(api,4096,false);
      Object handle=a.open(api);
      var identity=api.identity(handle,query,a.check());
      assertFalse(identity.directory());assertFalse(identity.reparse());assertFalse(identity.deleting());assertEquals(1,identity.links());
      String mapping=api.mapping(a.file.getRoot().toString(),names,a.check());
      String path=api.finalPath(handle,names,a.check());assertEquals("\\\\?\\"+a.file,path);
      byte[] bytes=new byte[33];int count=api.read(handle,bytes,0,33,read,a.check());assertEquals(32,count);
      assertEquals(-1,api.read(handle,bytes,32,1,read,a.check()));assertArrayEquals(new byte[33],bytes);
      facts="volume="+identity.volume()+";index="+Long.toUnsignedString(identity.index())+";bytes=32;eof=true;mapping="+mapping;
    }catch(Throwable error){failure=error;}finally{a.finish(failure,"OBSERVED",facts);}
  }
  @Test @Order(2) void descriptorRepresentation() throws Exception {
    Attempt a=new Attempt(1);Throwable failure=null;String status="FIXTURE_SETUP_FAILED",facts="";
    try{
      var api=new WindowsDeploymentCustody.NativeApi();Object output=a.memory(api,4096,false),handle=a.open(api);
      Object query=a.memory(api,4096,false);
      var identity=api.identity(handle,query,a.check());
      byte[] initial=readDescriptor(a,api,handle,output);
      byte[] owner=fixtureOwner(initial); Arrays.fill(initial,(byte)0);
      byte[] acl=fixtureAcl(owner);
      // Existing package mapping only; no duplicate CreateFile declaration or path setter fallback.
      var kernel=com.sun.jna.Native.load("kernel32",WindowsDeploymentCustody.KernelW.class);
      var setter=com.sun.jna.Native.load("advapi32",FixtureSecurity.class);
      var writeRecord=a.receipt.reserve(a.operation,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE,api);
      com.sun.jna.Pointer writeHandle;
      try{writeHandle=kernel.CreateFileW(new com.sun.jna.WString(a.file.toString()),0x60080,1,null,3,0x200000,null);}
      catch(Throwable error){a.receipt.finishEmptyFailure(a.operation,writeRecord);throw error;}
      if(writeHandle==null||com.sun.jna.Pointer.nativeValue(writeHandle)==-1){a.receipt.finishEmptyFailure(a.operation,writeRecord);throw new IllegalStateException("FIXTURE_SETUP_FAILED");}
      a.receipt.bindReturned(a.operation,writeRecord,writeHandle);
      assertEquals(identity,api.identity(writeHandle,query,a.check()));
      var aclRecord=a.receipt.reserve(a.operation,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.QUERY_MEMORY,api);
      Object allocated;
      try{allocated=api.allocate(4096);}catch(Throwable error){a.receipt.finishEmptyFailure(a.operation,aclRecord);throw error;}
      Object aclMemory=a.receipt.bindReturned(a.operation,aclRecord,allocated);
      ((com.sun.jna.Memory)aclMemory).write(0,acl,0,acl.length);
      applyFixture(a.file,a.file,identity,api.identity(writeHandle,query,a.check()),
          (flags)->setter.SetSecurityInfo(writeHandle,1,flags,null,null,(com.sun.jna.Pointer)aclMemory,null));
      a.receipt.check(a.operation);a.receipt.closeOwned(a.operation,aclRecord);
      status="FIXTURE_POSTCONDITION_FAILED";
      assertEquals(identity,api.identity(handle,query,a.check()));
      assertEquals(identity,api.identity(writeHandle,query,a.check()));
      byte[] raw=readDescriptor(a,api,handle,output);
      assertArrayEquals(owner,fixtureOwner(raw));
      int control=Short.toUnsignedInt(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getShort(2));
      assertTrue((control&0x1000)!=0,"FIXTURE_POSTCONDITION_FAILED");
      String root=a.directory.toString();String[] paths={a.file.toString(),root+"\\unused-mod",root+"\\unused-policy",root+"\\unused-binding",root+"\\unused-key"};
      byte[][] set={owner};
      var policy=new DeploymentCredentialLoader.TrustedDeploymentPolicy(root,paths,
          io.github.jinyiwei2012.luosandbox.security.ControlAuth.Role.SERVER_AUTHORITY,"dummy-peer","dummy-hub","dummy-key",
          new byte[32],identity.volume(),identity.index(),owner,new byte[][]{owner,owner,owner,owner,owner},set,set,set,set);
      var reason=WindowsDeploymentCustody.assessReason(raw,policy,false,0,a.profile);
      status=reason==WindowsDeploymentCustody.AssessmentReason.ACCEPTED?"PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED":"DESCRIPTOR_PROFILE_UNSUPPORTED";
      facts="fixture=NEW_DUMMY_PROTECTED_DACL;productionProfileChanged=false;testOwnerPolicy=FIXTURE_ONLY;originalReadbackUnmodified=true;profileId="+a.profile+";assessment="+reason
          +";descriptorBytes="+raw.length+";revision="+(raw.length==0?"NOT_AVAILABLE":Integer.toString(raw[0]&255))
          +";control="+(raw.length<4?"NOT_AVAILABLE":Integer.toString(control));
      Arrays.fill(raw,(byte)0);Arrays.fill(owner,(byte)0);Arrays.fill(acl,(byte)0);
    }catch(Throwable error){if(failure==null||!(failure instanceof Error)&&error instanceof Error)failure=error;}
    finally{a.finish(failure,status,facts);}
  }
  interface FixtureSecurity extends com.sun.jna.win32.StdCallLibrary {
    int SetSecurityInfo(com.sun.jna.Pointer handle,int objectType,int information,com.sun.jna.Pointer owner,
        com.sun.jna.Pointer group,com.sun.jna.Pointer dacl,com.sun.jna.Pointer sacl);
  }
  interface FixtureSetter { int set(int information); }
  static void applyFixture(Path created,Path target,WindowsDeploymentCustody.Identity expected,
      WindowsDeploymentCustody.Identity actual,FixtureSetter setter){
    if(!created.equals(target)||!expected.equals(actual)||actual.directory()||actual.reparse()||actual.deleting()||actual.links()!=1)
      throw new IllegalStateException("FIXTURE_SETUP_FAILED");
    if(setter.set(0x80000004)!=0)throw new IllegalStateException("FIXTURE_SETUP_FAILED");
  }
  static byte[] fixtureOwner(byte[] raw){
    if(raw==null||raw.length<20||raw.length>4096||raw[0]!=1)throw new IllegalStateException("FIXTURE_SETUP_FAILED");
    var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    int flags=Short.toUnsignedInt(b.getShort(2)),offset=b.getInt(4);
    if((flags&0x8000)==0||offset<20||offset>raw.length-8)throw new IllegalStateException("FIXTURE_SETUP_FAILED");
    int length=8+(raw[offset+1]&255)*4;
    if(raw[offset]!=1||length<12||length>68||offset>raw.length-length)throw new IllegalStateException("FIXTURE_SETUP_FAILED");
    return Arrays.copyOfRange(raw,offset,offset+length);
  }
  static byte[] fixtureAcl(byte[] sid){
    if(sid==null||sid.length<12||sid.length>68||sid[0]!=1||sid.length!=8+(sid[1]&255)*4)throw new IllegalArgumentException("fixture SID shape");
    byte[] acl=new byte[16+sid.length];var b=ByteBuffer.wrap(acl).order(ByteOrder.LITTLE_ENDIAN);
    acl[0]=2;b.putShort(2,(short)acl.length);b.putShort(4,(short)1);b.putShort(10,(short)(8+sid.length));b.putInt(12,0x00120089);
    System.arraycopy(sid,0,acl,16,sid.length);return acl;
  }
  static String assessFixture(byte[] original,DeploymentCredentialLoader.TrustedDeploymentPolicy policy){
    return WindowsDeploymentCustody.assessReason(original,policy,false,0)==WindowsDeploymentCustody.AssessmentReason.ACCEPTED
        ?"PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED":"DESCRIPTOR_PROFILE_UNSUPPORTED";
  }
  private static byte[] readDescriptor(Attempt a,WindowsDeploymentCustody.Api api,Object handle,Object output){
    var record=a.receipt.reserve(a.operation,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);
    boolean success=false;Object pointer=null;boolean ambiguous=false;Throwable first=null;
    try{success=api.queryDescriptor(handle,output);}catch(Throwable e){first=e;}
    try{a.receipt.check(a.operation);pointer=api.descriptorPointer(output);}catch(Throwable e){ambiguous=true;first=prefer(first,e);}
    try{a.receipt.reconcileDescriptor(a.operation,record,success&&first==null,pointer,ambiguous);}catch(Throwable e){first=prefer(first,e);}
    if(first!=null)raise(first);
    long size=api.extent(pointer);a.receipt.check(a.operation);
    if(size<20||size>4096)throw new IllegalStateException("FIXTURE_SETUP_FAILED");
    byte[] raw=api.descriptorBytes(pointer,(int)size);a.receipt.check(a.operation);
    a.receipt.closeOwned(a.operation,record);return raw;
  }
  @Test @Order(3) void compatibleReadSharing() throws Exception {
    Attempt a=new Attempt(2);Throwable failure=null;String facts="";
    try{
      var api=new WindowsDeploymentCustody.NativeApi();Object query=a.memory(api,4096,false);
      Object first=a.open(api),second=a.open(api);
      assertEquals(api.identity(first,query,a.check()),api.identity(second,query,a.check()));
      facts="COMPATIBLE_READ_SHARING;opens=2;writeDeleteDenial=NOT_TESTED";
    }catch(Throwable error){failure=error;}finally{a.finish(failure,"OBSERVED",facts);}
  }
  private static String required(String name){String s=System.getenv(name);assertNotNull(s,name);assertFalse(s.isBlank(),name);return s;}
  static Throwable prefer(Throwable first,Throwable later){return first==null||!(first instanceof Error)&&later instanceof Error?later:first;}
  static void raise(Throwable failure){if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)throw e;throw new IllegalStateException("mechanism operation failed");}
  static void independent(Throwable first,Runnable...steps){for(Runnable step:steps){try{step.run();}catch(Throwable later){first=prefer(first,later);}}if(first!=null)raise(first);}
  static void finishSteps(Throwable first,java.util.function.BooleanSupplier unknown,Runnable poison,
      java.util.function.Consumer<String> emit,String success,Runnable...steps){
    for(Runnable step:steps){try{step.run();}catch(Throwable later){first=prefer(first,later);}}
    boolean uncertain=unknown.getAsBoolean();
    if(uncertain||first!=null)poison.run();
    try{emit.accept(uncertain?"CLEANUP_UNKNOWN":first!=null?(success.startsWith("FIXTURE_")?success:"FAILED"):success);}catch(Throwable later){first=prefer(first,later);poison.run();}
    if(first!=null)raise(first);
  }
  private static void captureIdentity(Path output,String nonce)throws Exception{
    var properties=new java.util.Properties();properties.setProperty("nonce",nonce);
    properties.setProperty("assessmentProfile",required("JAVA_CUSTODY_ASSESSMENT_PROFILE"));
    for(String key:new String[]{"java.vendor","java.runtime.version","os.name","os.version","os.arch","sun.arch.data.model"})
      properties.setProperty(key,System.getProperty(key,"NOT_RETAINED"));
    properties.setProperty("pointerBytes",Integer.toString(com.sun.jna.Native.POINTER_SIZE));
    properties.setProperty("structureSize","NOT_EXPOSED: production uses bounded Memory and explicit DWORD offsets, no Structure instance");
    properties.setProperty("sourceDwordBytes","4");properties.setProperty("sourceLegacyInfoBytes","52");
    String[] resources={"com/sun/jna/Native.class","com/sun/jna/platform/win32/Kernel32.class",
        "io/github/jinyiwei2012/luosandbox/transport/WindowsDeploymentMechanismNativeTest.class",
        "io/github/jinyiwei2012/luosandbox/transport/WindowsDeploymentCustody$NativeApi.class",
        "io/github/jinyiwei2012/luosandbox/transport/DeploymentCredentialLoader$CustodyAttemptReceipt.class"};
    for(int i=0;i<resources.length;i++){
      var url=WindowsDeploymentMechanismNativeTest.class.getClassLoader().getResource(resources[i]);assertNotNull(url);
      properties.setProperty("resource."+i,url.toExternalForm());
      try(var input=url.openStream()){
        byte[] bytes=input.readNBytes(1048577);assertTrue(bytes.length<=1048576);
        properties.setProperty("classSha."+i,sha(bytes));
        Files.write(output.resolve("executed-class-"+i+".bin"),bytes,StandardOpenOption.CREATE_NEW);
      }
      if(i<2){
        assertTrue(url.openConnection() instanceof java.net.JarURLConnection);
        var jar=((java.net.JarURLConnection)url.openConnection()).getJarFileURL();assertEquals("file",jar.getProtocol());
        Path path=Path.of(jar.toURI());long size=Files.size(path);assertTrue(size>0&&size<=16777216);
        properties.setProperty("jarPath."+i,path.toString());properties.setProperty("jarSize."+i,Long.toString(size));
        properties.setProperty("jarSha."+i,sha(Files.readAllBytes(path)));
        properties.setProperty("jarUse."+i,i==0?"USED_CORE":"AVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING");
      }
    }
    try(var stream=Files.newOutputStream(output.resolve("runtime-identity.properties"),StandardOpenOption.CREATE_NEW)){properties.store(stream,"mechanism identity; no secret data");}
  }
  private static String sha(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
  private static void result(int index,String nonce,String status,String facts){
    System.out.println("JAVA_CUSTODY_MECHANISM "+nonce+" "+index+" "+status+" "+facts);
    System.out.println("JAVA_CUSTODY_RUNTIME "+System.getProperty("java.vendor")+" "+System.getProperty("java.runtime.version")+" "+System.getProperty("os.arch"));
    System.out.println("JAVA_CUSTODY_JNA_RESOURCE "+WindowsDeploymentMechanismNativeTest.class.getClassLoader().getResource("com/sun/jna/Native.class"));
  }
}

class WindowsDeploymentMechanismSafetyTest {
  @Test void protectedFixtureAclIsExactAndSetterIsBoundToCreatedIdentity(){
    byte[] sid={1,1,0,0,0,0,0,5,18,0,0,0};byte[] acl=WindowsDeploymentMechanismNativeTest.fixtureAcl(sid);
    var b=ByteBuffer.wrap(acl).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(28,acl.length);assertEquals(0,acl.length%4);assertEquals(2,acl[0]);assertEquals(1,b.getShort(4));
    assertEquals(0,acl[8]);assertEquals(0,acl[9]);assertEquals(0x00120089,b.getInt(12));
    assertArrayEquals(sid,Arrays.copyOfRange(acl,16,acl.length));
    var id=new WindowsDeploymentCustody.Identity(1,2,false,false,false,1,32);int[] calls={0};
    Path path=Path.of("dummy");
    WindowsDeploymentMechanismNativeTest.applyFixture(path,path,id,id,flags->{calls[0]++;assertEquals(0x80000004,flags);return 0;});
    assertThrows(RuntimeException.class,()->WindowsDeploymentMechanismNativeTest.applyFixture(path,Path.of("other"),id,id,flags->{calls[0]++;return 0;}));
    assertEquals(1,calls[0]);
    assertThrows(RuntimeException.class,()->WindowsDeploymentMechanismNativeTest.applyFixture(path,path,id,id,flags->5));
    assertThrows(RuntimeException.class,()->WindowsDeploymentMechanismNativeTest.fixtureAcl(new byte[12]));
  }
  @Test void originalDescriptorRejectionIsNotRewritten(){
    byte[] raw=new byte[40];raw[0]=1;ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(2,(short)0x8004);
    byte[] original=raw.clone();
    assertEquals("DESCRIPTOR_PROFILE_UNSUPPORTED",WindowsDeploymentMechanismNativeTest.assessFixture(raw,DeploymentCredentialLoaderTest.policy()));
    assertArrayEquals(original,raw);
    assertThrows(RuntimeException.class,()->WindowsDeploymentMechanismNativeTest.fixtureOwner(raw));
  }
  @Test void evidenceOrDeleteFailureNeverEmitsObservedAndKeepsFirstError(){
    for(boolean fatalBody:new boolean[]{false,true}){
      Error body=new AssertionError("body");var statuses=new java.util.ArrayList<String>();boolean[] poisoned={false};int[] calls={0};
      Throwable expected=assertThrows(Throwable.class,()->WindowsDeploymentMechanismNativeTest.finishSteps(fatalBody?body:null,
          ()->false,()->poisoned[0]=true,statuses::add,"OBSERVED",
          ()->{calls[0]++;throw new IllegalStateException("evidence");},()->{calls[0]++;throw new AssertionError("delete");}));
      if(fatalBody)assertSame(body,expected);
      assertEquals(java.util.List.of("FAILED"),statuses);assertTrue(poisoned[0]);assertEquals(2,calls[0]);
    }
  }
  @Test void bodyErrorWinsReleaseOrdinaryAndEvidenceErrorWhileEveryStepRuns(){
    for(boolean reportError:new boolean[]{false,true}){
      Error first=new AssertionError("body");int[] calls={0};
      assertSame(first,assertThrows(Error.class,()->WindowsDeploymentMechanismNativeTest.independent(first,
          ()->{calls[0]++;throw new IllegalStateException("release");},
          ()->{calls[0]++;if(reportError)throw new AssertionError("report");},()->calls[0]++)));
      assertEquals(3,calls[0]);
    }
  }
  @Test void cleanupErrorOutranksOrdinaryAndOwnerRetentionStepStillRuns(){
    Error cleanup=new AssertionError("cleanup");boolean[] retained={false};
    assertSame(cleanup,assertThrows(Error.class,()->WindowsDeploymentMechanismNativeTest.independent(new IllegalStateException(),
        ()->{throw cleanup;},()->retained[0]=true,()->{})));
    assertTrue(retained[0]);
  }
}
