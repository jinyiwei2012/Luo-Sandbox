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
    Object operation;
    Attempt(int index) throws Exception {
      Assumptions.assumeTrue("1".equals(System.getenv("RUN_JAVA_CUSTODY_MECHANISM_NATIVE")), "explicit native mechanism opt-in required");
      assertTrue(System.getProperty("os.name").startsWith("Windows"));
      nonce = required("JAVA_CUSTODY_RUN_NONCE"); assertTrue(nonce.matches("[0-9a-f]{32}"));
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
    Attempt a=new Attempt(1);Throwable failure=null;String status="OBSERVED",facts="";
    try{
      var api=new WindowsDeploymentCustody.NativeApi();Object output=a.memory(api,4096,false),handle=a.open(api);
      var record=a.receipt.reserve(a.operation,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);
      boolean success=false;Object pointer=null;boolean ambiguous=false;
      try{success=api.queryDescriptor(handle,output);}catch(Throwable error){failure=error;}
      try{a.receipt.check(a.operation);pointer=api.descriptorPointer(output);}catch(Throwable error){ambiguous=true;if(failure==null||!(failure instanceof Error)&&error instanceof Error)failure=error;}
      try{a.receipt.reconcileDescriptor(a.operation,record,success&&failure==null,pointer,ambiguous);}
      catch(Throwable error){failure=prefer(failure,error);}
      if(failure!=null)raise(failure);
      long extent=api.extent(pointer);a.receipt.check(a.operation);
      if(extent<20||extent>4096){status="DESCRIPTOR_PROFILE_UNSUPPORTED";facts="query=true;boundedExtent=false";}
      else{
        byte[] raw=api.descriptorBytes(pointer,(int)extent);a.receipt.check(a.operation);
        int control=Short.toUnsignedInt(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getShort(2));
        boolean relative=(control&0x8000)!=0;
        boolean supported=raw[0]==1&&control==0x9004;
        facts="query=true;extent="+extent+";revision="+(raw[0]&255)+";control="+control+";selfRelative="+relative+";representationCandidate="+supported;
        if(!supported)status="DESCRIPTOR_PROFILE_UNSUPPORTED";
        Arrays.fill(raw,(byte)0);
      }
    }catch(Throwable error){if(failure==null||!(failure instanceof Error)&&error instanceof Error)failure=error;}
    finally{a.finish(failure,status,facts);}
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
    try{emit.accept(uncertain?"CLEANUP_UNKNOWN":first!=null?"FAILED":success);}catch(Throwable later){first=prefer(first,later);poison.run();}
    if(first!=null)raise(first);
  }
  private static void captureIdentity(Path output,String nonce)throws Exception{
    var properties=new java.util.Properties();properties.setProperty("nonce",nonce);
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
