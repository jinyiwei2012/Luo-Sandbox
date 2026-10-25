package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Default-off, test-policy-only loader experiment. Never an activation decision. */
class DeploymentCredentialLoaderNativeTest {
  private static Owner retained;
  private static final Owner[] ownerRegistry=new Owner[1];
  static final class Trust {
    private final byte[][] content,security,namespace;
    Trust(String content,String security,String namespace){this.content=parse(content);this.security=parse(security);this.namespace=parse(namespace);}
    static byte[][] parse(String text){
      if(text==null||text.isEmpty())throw new IllegalArgumentException("PREDECLARED_SIDS_REQUIRED");
      String[] parts=text.split(",",-1);if(parts.length<1||parts.length>16)throw new IllegalArgumentException("SID_SET_INVALID");
      byte[][] values=new byte[parts.length][];
      for(int i=0;i<parts.length;i++){
        values[i]=HexFormat.of().parseHex(parts[i]);byte[] sid=values[i];
        if(sid.length<12||sid.length>68||sid[0]!=1||(sid[1]&255)>15||sid.length!=8+(sid[1]&255)*4)throw new IllegalArgumentException("SID_INVALID");
        for(int j=0;j<i;j++)if(Arrays.equals(sid,values[j]))throw new IllegalArgumentException("SID_DUPLICATE");
      }
      return values;
    }
    DeploymentCredentialLoader.TrustedDeploymentPolicy policy(Path root,byte[] owner,WindowsDeploymentCustody.Identity identity){
      String r=root.toString();return new DeploymentCredentialLoader.TrustedDeploymentPolicy(r,
          new String[]{r+"\\manifest",r+"\\mod",r+"\\policy",r+"\\binding",r+"\\key"},
          io.github.jinyiwei2012.luosandbox.security.ControlAuth.Role.SERVER_AUTHORITY,"peer","hub","key",
          DeploymentCredentialLoaderTest.digest(DeploymentCredentialLoaderTest.manifest()),identity.volume(),identity.index(),owner,
          new byte[][]{owner,owner,owner,owner,owner},content,security,namespace,new byte[][]{owner});
    }
  }
  static final class Owner {
    DeploymentCredentialLoader.CustodyAttemptReceipt probe,setup,loader;
    final DeploymentCredentialLoader.HandoffSlot slot=new DeploymentCredentialLoader.HandoffSlot();
    Path root;boolean unknown;boolean created;
    final Path[] children=new Path[5];
    final WindowsDeploymentCustody.Identity[] identities=new WindowsDeploymentCustody.Identity[6];
    final CreatedTarget[] targets=new CreatedTarget[6];
    String mapping;long volume;
    void create(Path prospective,java.util.function.Consumer<Path> creator){
      if(created||root!=null)throw new IllegalStateException("OWNER_ALREADY_USED");
      creator.accept(prospective);root=prospective;created=true;
    }
    void capture(int index,Path path,boolean directory)throws Exception{
      if(targets[index]!=null)throw new IllegalStateException("CREATION_ANCHOR_ALREADY_BOUND");
      targets[index]=CreatedTarget.capture(path,directory);
    }
    void bind(int index,Path path,boolean directory,WindowsDeploymentCustody.Identity identity,String heldPath)throws Exception{
      bind(index,path,directory,identity,heldPath,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
    }
    void bind(int index,Path path,boolean directory,WindowsDeploymentCustody.Identity identity,String heldPath,BasicFileAttributes attributes){
      if(targets[index]==null)throw new IllegalStateException("CREATION_ANCHOR_MISSING");
      targets[index].verify(path,directory,attributes);
      requireNewTarget(root,path,directory,identity);
      if(!heldPath.equalsIgnoreCase("\\\\?\\"+path))throw new IllegalStateException("HELD_PATH_CHANGED");
      if(identities[index]==null)identities[index]=identity;
      else if(!identities[index].equals(identity))throw new IllegalStateException("HELD_IDENTITY_CHANGED");
    }
    void ancestryIdentity(String observedMapping,WindowsDeploymentCustody.Identity identity){
      if(mapping==null){mapping=observedMapping;volume=identity.volume();}
      else if(!mapping.equals(observedMapping)||volume!=identity.volume())throw new IllegalArgumentException("ANCESTRY_CHANGED");
    }
    void confirm(DeploymentCredentialLoader.CustodyAttemptReceipt receipt){
      var evidence=receipt.snapshot();
      if(evidence.unknown()||!evidence.state().equals("RELEASED")||evidence.retained()!=0){unknown=true;throw new IllegalStateException("NATIVE_CLEANUP_UNKNOWN");}
    }
    void target(Path target,boolean directory,WindowsDeploymentCustody.Identity actual){
      if(!created)throw new IllegalStateException("ROOT_NOT_OWNED");
      int index=directory?0:-1;
      if(directory&&!target.equals(root))throw new IllegalArgumentException("UNOWNED_TARGET");
      if(!directory)for(int i=0;i<children.length;i++)if(target.equals(children[i]))index=i+1;
      if(index<0||identities[index]==null||!identities[index].equals(actual))throw new IllegalArgumentException("REPLACED_OR_UNOWNED_TARGET");
      requireNewTarget(root,target,directory,actual);
    }
  }
  record CreatedTarget(Path path,Object fileKey,boolean directory){
    CreatedTarget{if(fileKey==null)throw new IllegalStateException("CREATION_FILE_KEY_UNAVAILABLE");}
    static CreatedTarget capture(Path path,boolean directory)throws Exception{
      var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
      var target=new CreatedTarget(path,attributes.fileKey(),directory);target.verify(path,directory,attributes);return target;
    }
    void verify(Path observed,boolean expectedDirectory,BasicFileAttributes attributes){
      if(!path.equals(observed)||directory!=expectedDirectory||!fileKey.equals(attributes.fileKey())||attributes.isSymbolicLink()
          ||attributes.isOther()||attributes.isDirectory()!=directory||!directory&&!attributes.isRegularFile())throw new IllegalStateException("CREATED_TARGET_CHANGED");
    }
  }
  static Owner claimOwner(){return retained=claimOwner(ownerRegistry);}
  static Owner claimOwner(Owner[] registry){
    if(registry.length!=1||registry[0]!=null)throw new IllegalStateException("NATIVE_OWNER_ALREADY_RETAINED");
    return registry[0]=new Owner();
  }
  static void independent(Throwable first,Runnable...steps){WindowsDeploymentMechanismNativeTest.independent(first,steps);}
  static java.util.List<Path> ancestry(Path output){var result=new java.util.ArrayList<Path>();for(Path p=output;p!=null;p=p.getParent())result.add(p);java.util.Collections.reverse(result);return result;}
  static void correlate(Path path,WindowsDeploymentCustody.Identity identity,String finalPath){
    if(!identity.directory()||identity.reparse()||identity.deleting()||identity.volume()==0||identity.index()==0||!finalPath.equalsIgnoreCase("\\\\?\\"+path))throw new IllegalArgumentException("ANCESTRY_UNSUPPORTED");
  }
  static final DeploymentCredentialLoader.CustodyAssessment ASSESSMENT=new DeploymentCredentialLoader.CustodyAssessment(
      WindowsDeploymentCustody.AssessmentProfile.PROTECTED_AUTO_INHERITED_SNAPSHOT_V1,
      WindowsDeploymentCustody.AssessmentLayout.BOUNDED_SELF_RELATIVE_LAYOUT_V1);
  static final class Probe {
    final DeploymentCredentialLoader.CustodyAttemptReceipt receipt;final WindowsDeploymentCustody.Api api;final Object token;
    Probe(DeploymentCredentialLoader.CustodyAttemptReceipt receipt,WindowsDeploymentCustody.Api api){this.receipt=receipt;this.api=api;receipt.claim();token=receipt.beginOperation();}
    Object memory(){var r=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.QUERY_MEMORY,api);Object v;try{v=api.allocate(4096);}catch(Throwable e){receipt.finishEmptyFailure(token,r);throw e;}return receipt.bindReturned(token,r,v);}
    Object open(Path path,boolean directory){var r=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE,api);Object v;try{v=api.open(path.toString(),directory);}catch(Throwable e){receipt.finishEmptyFailure(token,r);throw e;}return receipt.bindReturned(token,r,v);}
    Runnable check(){return ()->receipt.check(token);}
    byte[] descriptor(Object handle,Object output){
      var r=receipt.reserve(token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR,api);
      boolean ok=false,ambiguous=false;Object pointer=null;Throwable first=null;
      try{ok=api.queryDescriptor(handle,output);}catch(Throwable e){first=e;}
      try{receipt.check(token);pointer=api.descriptorPointer(output);}catch(Throwable e){ambiguous=true;first=WindowsDeploymentMechanismNativeTest.prefer(first,e);}
      try{receipt.reconcileDescriptor(token,r,ok&&first==null,pointer,ambiguous);}catch(Throwable e){first=WindowsDeploymentMechanismNativeTest.prefer(first,e);}
      if(first!=null)WindowsDeploymentMechanismNativeTest.raise(first);
      long size=api.extent(pointer);receipt.check(token);if(size<20||size>4096)throw new IllegalStateException("DESCRIPTOR_EXTENT_UNSUPPORTED");
      byte[] raw=api.descriptorBytes(pointer,(int)size);receipt.check(token);receipt.closeOwned(token,r);return raw;
    }
    void finish(Throwable first){independent(first,()->receipt.endOperation(token),()->{Throwable error=receipt.release(null);if(error!=null)WindowsDeploymentMechanismNativeTest.raise(error);});}
  }
  @Test void dummyFiveFileLoader() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_JAVA_LOADER_NATIVE")),"explicit loader-native opt-in required");
    assertTrue(System.getProperty("os.name").startsWith("Windows"));
    String nonce=System.getenv("JAVA_LOADER_NONCE");assertNotNull(nonce);assertTrue(nonce.matches("[0-9a-f]{32}"));
    Path output=Path.of(System.getenv("JAVA_LOADER_OUTPUT"));assertTrue(output.isAbsolute());
    Path parent=Path.of("C:\\Users\\Administrator\\AppData\\Local\\Temp\\opencode").toRealPath();assertTrue(output.toRealPath().startsWith(parent));
    Trust trust;
    try{trust=new Trust(System.getenv("JAVA_LOADER_CONTENT_SIDS"),System.getenv("JAVA_LOADER_SECURITY_SIDS"),System.getenv("JAVA_LOADER_NAMESPACE_SIDS"));}
    catch(IllegalArgumentException missing){emit(nonce,"BLOCKED_PREREQUISITE");return;}
    claimOwner();String status="BLOCKED_PREREQUISITE";Throwable first=null;
    System.out.println("JAVA_LOADER_NATIVE_PHASE_ATTEMPTED "+nonce);
    try{
      identityStage(()->{try{captureIdentity(output,nonce);}catch(Exception e){throw new IllegalStateException("IDENTITY_EVIDENCE_FAILED");}});
      // All preexisting ancestry is read-only. Reuse the probe slot only after confirmed release.
      var paths=ancestry(output);
      for(Path path:paths){
        retained.probe=new DeploymentCredentialLoader.CustodyAttemptReceipt();var api=new WindowsDeploymentCustody.NativeApi();System.out.println("JAVA_LOADER_API_INITIALIZED "+nonce);var probe=new Probe(retained.probe,api);Throwable failure=null;
        try{Object query=probe.memory(),descriptor=probe.memory(),names=probe.memory();
          String mapping=api.mapping(path.getRoot().toString(),names,probe.check());assertTrue(mapping.matches("\\\\Device\\\\HarddiskVolume[1-9][0-9]*"));
          Object handle=probe.open(path,true);var identity=api.identity(handle,query,probe.check());
          correlate(path,identity,api.finalPath(handle,names,probe.check()));
          retained.ancestryIdentity(mapping,identity);
          byte[] raw=probe.descriptor(handle,descriptor);byte[] owner=WindowsDeploymentMechanismNativeTest.fixtureOwner(raw);
          // Prospective paths are shape-only policy inputs, never filesystem evidence.
          var policy=trust.policy(parent.resolve("unused-prospective-root"),owner,identity);
          if(WindowsDeploymentCustody.assessReason(raw,policy,true,-2,ASSESSMENT.profile(),ASSESSMENT.layout())!=WindowsDeploymentCustody.AssessmentReason.ACCEPTED)throw new IllegalArgumentException("ANCESTRY_UNSUPPORTED");
        }catch(Throwable e){failure=e;}finally{final Throwable body=failure;independent(body,()->probe.finish(null),()->retained.confirm(retained.probe));}
      }
      status="FAILED";retained.create(output.resolve("dummy-loader-root"),p->{try{Files.createDirectory(p);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
      retained.capture(0,retained.root,true);
      String[] names={"manifest","mod","policy","binding","key"};
      for(int i=0;i<names.length;i++){Path child=retained.root.resolve(names[i]);Files.createFile(child);retained.children[i]=child;retained.capture(i+1,child,false);
        Files.write(child,names[i].equals("manifest")?DeploymentCredentialLoaderTest.manifest():names[i].equals("key")?new byte[32]:new byte[]{1},StandardOpenOption.TRUNCATE_EXISTING);}
      retained.setup=new DeploymentCredentialLoader.CustodyAttemptReceipt();var api=new WindowsDeploymentCustody.NativeApi();var setup=new Probe(retained.setup,api);Throwable setupFailure=null;
      byte[] rootOwner=null;WindowsDeploymentCustody.Identity rootId=null;
      try{
        var kernel=com.sun.jna.Native.load("kernel32",WindowsDeploymentCustody.KernelW.class);
        var setter=com.sun.jna.Native.load("advapi32",WindowsDeploymentMechanismNativeTest.FixtureSecurity.class);
        Object query=setup.memory(),descriptor=setup.memory(),aclMemory=setup.memory();
        for(int i=-1;i<5;i++){
          Path path=i<0?retained.root:retained.root.resolve(names[i]);boolean directory=i<0;
          var record=retained.setup.reserve(setup.token,DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE,api);
          com.sun.jna.Pointer handle;
          try{handle=kernel.CreateFileW(new com.sun.jna.WString(path.toString()),0x60080,1,null,3,directory?0x2200000:0x200000,null);}
          catch(Throwable error){retained.setup.finishEmptyFailure(setup.token,record);throw error;}
          if(handle==null||com.sun.jna.Pointer.nativeValue(handle)==-1){retained.setup.finishEmptyFailure(setup.token,record);throw new IllegalStateException("SETUP_OPEN_FAILED");}
          retained.setup.bindReturned(setup.token,record,handle);var identity=api.identity(handle,query,setup.check());
          retained.bind(i+1,path,directory,identity,api.finalPath(handle,query,setup.check()));
          byte[] owner=WindowsDeploymentMechanismNativeTest.fixtureOwner(setup.descriptor(handle,descriptor));
          if(i<0){rootOwner=owner;rootId=identity;}
          byte[] acl=WindowsDeploymentMechanismNativeTest.fixtureAcl(owner);if(directory)ByteBuffer.wrap(acl).order(ByteOrder.LITTLE_ENDIAN).putInt(12,0x001f01ff);
          ((com.sun.jna.Memory)aclMemory).write(0,acl,0,acl.length);
          retained.target(path,directory,identity);
          assertEquals("\\\\?\\"+path,api.finalPath(handle,query,setup.check()));
          if(setter.SetSecurityInfo(handle,1,0x80000004,null,null,(com.sun.jna.Pointer)aclMemory,null)!=0)throw new IllegalStateException("SETUP_ACL_FAILED");
          retained.setup.check(setup.token);assertEquals(identity,api.identity(handle,query,setup.check()));
          retained.bind(i+1,path,directory,api.identity(handle,query,setup.check()),api.finalPath(handle,query,setup.check()));
          assertArrayEquals(owner,WindowsDeploymentMechanismNativeTest.fixtureOwner(setup.descriptor(handle,descriptor)));
          retained.setup.closeOwned(setup.token,record);
        }
      }catch(Throwable e){setupFailure=e;}finally{final Throwable body=setupFailure;independent(body,()->setup.finish(null),()->retained.confirm(retained.setup));}
      var policy=trust.policy(retained.root,rootOwner,rootId);
      retained.loader=new DeploymentCredentialLoader.CustodyAttemptReceipt();status="BLOCKED_LOADER_REJECTION";
      new DeploymentCredentialLoader(new WindowsDeploymentCustody()).load(policy,ASSESSMENT,retained.loader,retained.slot);
      status="FAILED"; // POST_RETURN_VALIDATION: subsequent failures are never loader-profile rejection.
      retained.confirm(retained.loader);assertEquals(0,retained.loader.snapshot().retained());assertEquals("READY",retained.slot.state());
      var value=retained.slot.claim();assertEquals(ASSESSMENT,value.assessment());assertEquals("peer",value.manifest.peerInstallId());assertEquals("hub",value.manifest.hubInstallId());
      verifyCopyThenClose(value.credential(),retained.slot);status="DUMMY_LOADER_NATIVE_PASS_LIMITED";
    }catch(Throwable e){first=e;if(retained.unknown||e instanceof Error||e instanceof EvidenceFailure)status="FAILED";}
    finally{
      final String[] outcome={status};final Throwable body=first;
      try{finalStage(body,outcome,
          ()->{try{retained.slot.close();}catch(Throwable e){outcome[0]="FAILED";throw e;}},
          ()->{if(retained.loader!=null&&retained.loader.snapshot().unknown()){retained.unknown=true;outcome[0]="FAILED";}},
          ()->{if(retained.created){outcome[0]="FAILED";System.out.println("JAVA_LOADER_FIXTURE_CLEANUP "+nonce+" RETAINED_NOT_DELETED");}},
          ()->emit(nonce,outcome[0]));}
      catch(Error fatal){throw fatal;}
      catch(RuntimeException ordinary){if(outcome[0].equals("FAILED"))throw ordinary;}
      if(outcome[0].equals("FAILED"))throw new IllegalStateException("native loader experiment failed");
    }
  }
  static final class EvidenceFailure extends RuntimeException { EvidenceFailure(){super("EVIDENCE_FAILED",null,false,false);} }
  static void identityStage(Runnable collect){try{collect.run();}catch(Error fatal){throw fatal;}catch(RuntimeException failed){throw new EvidenceFailure();}}
  static void finalStage(Throwable first,String[] outcome,Runnable...steps){
    for(Runnable step:steps){try{step.run();}catch(Throwable later){outcome[0]="FAILED";first=WindowsDeploymentMechanismNativeTest.prefer(first,later);}}
    if(first!=null)WindowsDeploymentMechanismNativeTest.raise(first);
  }
  private static void captureIdentity(Path output,String nonce)throws Exception{
    var properties=new java.util.Properties();properties.setProperty("nonce",nonce);
    properties.setProperty("profile",ASSESSMENT.profile().name());properties.setProperty("layout",ASSESSMENT.layout().name());
    for(String key:new String[]{"java.vendor","java.runtime.version","os.name","os.version","os.arch","sun.arch.data.model"})properties.setProperty(key,System.getProperty(key,"NOT_EXPOSED"));
    properties.setProperty("pointerBytes",Integer.toString(com.sun.jna.Native.POINTER_SIZE));
    properties.setProperty("structureSize","NOT_EXPOSED: bounded Memory and explicit source offsets");
    String prefix="io.github.jinyiwei2012.luosandbox.transport.";
    String[] names={"com.sun.jna.Native","com.sun.jna.platform.win32.Kernel32",
        prefix+"DeploymentCredentialLoaderNativeTest",prefix+"DeploymentCredentialLoaderNativeTest$CreatedTarget",
        prefix+"DeploymentCredentialLoaderNativeTest$Trust",prefix+"DeploymentCredentialLoaderNativeTest$Probe",
        prefix+"DeploymentCredentialLoaderNativeTest$Owner",prefix+"DeploymentCredentialLoaderNativeTest$EvidenceFailure",
        prefix+"DeploymentCredentialLoaderTest",prefix+"WindowsDeploymentMechanismNativeTest",
        prefix+"WindowsDeploymentMechanismNativeTest$FixtureSecurity",prefix+"DeploymentCredentialLoader",
        prefix+"DeploymentCredentialLoader$CustodyAttemptReceipt",prefix+"DeploymentCredentialLoader$CustodyAttemptReceipt$NativeRecord",
        prefix+"DeploymentCredentialLoader$HandoffSlot",prefix+"DeploymentCredentialLoader$Provisioning",prefix+"DeploymentCredentialLoader$Secret",
        prefix+"DeploymentCredentialLoader$TrustedDeploymentPolicy",prefix+"DeploymentCredentialLoader$CustodyAssessment",
        prefix+"WindowsDeploymentCustody",prefix+"WindowsDeploymentCustody$NativeApi",prefix+"WindowsDeploymentCustody$Snapshot"};
    properties.setProperty("classCount",Integer.toString(names.length));
    String[] resources=Arrays.stream(names).map(n->n.replace('.','/')+".class").toArray(String[]::new);
    for(int i=0;i<resources.length;i++){
      Class<?> type=Class.forName(names[i],false,DeploymentCredentialLoaderNativeTest.class.getClassLoader());
      ClassLoader defining=type.getClassLoader();
      properties.setProperty("className."+i,names[i]);
      var resource=defining.getResource(resources[i]);assertNotNull(resource);properties.setProperty("resource."+i,resource.toExternalForm());
      try(var input=resource.openStream()){
        byte[] bytes=input.readNBytes(1048577);assertTrue(bytes.length<=1048576);
        properties.setProperty("classSha."+i,sha(bytes));Files.write(output.resolve("loader-class-"+i+".bin"),bytes,StandardOpenOption.CREATE_NEW);
      }
      if(i<2){assertTrue(resource.openConnection() instanceof java.net.JarURLConnection);
        var jar=((java.net.JarURLConnection)resource.openConnection()).getJarFileURL();assertEquals("file",jar.getProtocol());Path path=Path.of(jar.toURI());
        long size=Files.size(path);assertTrue(size>0&&size<=16777216);properties.setProperty("jarPath."+i,path.toString());properties.setProperty("jarSize."+i,Long.toString(size));
        properties.setProperty("jarSha."+i,sha(Files.readAllBytes(path)));properties.setProperty("jarUse."+i,i==0?"USED_CORE":"AVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING");}
    }
    try(var stream=Files.newOutputStream(output.resolve("loader-runtime.properties"),StandardOpenOption.CREATE_NEW)){properties.store(stream,"dummy loader identity, no secrets");}
  }
  private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
  static void requireNewTarget(Path root,Path target,boolean directory,WindowsDeploymentCustody.Identity id){
    if(!(directory?target.equals(root):target.getParent().equals(root))||id.directory()!=directory||id.reparse()||id.deleting()||!directory&&id.links()!=1)throw new IllegalArgumentException("SETUP_TARGET_INVALID");
  }
  static void verifyCopyThenClose(V2PeerConnector.CredentialOwner credential,DeploymentCredentialLoader.HandoffSlot slot){
    verifyCopyThenClose(credential::copy,copy->assertArrayEquals(new byte[32],copy),copy->Arrays.fill(copy,(byte)0),slot::close);
    assertThrows(RuntimeException.class,credential::copy);assertThrows(RuntimeException.class,slot::acknowledgeConstruction);
  }
  static void verifyCopyThenClose(java.util.function.Supplier<byte[]> copyPort,java.util.function.Consumer<byte[]> compare,
      java.util.function.Consumer<byte[]> wipe,Runnable close){
    byte[] copy=null;Throwable first=null;
    try{copy=copyPort.get();compare.accept(copy);}catch(Throwable e){first=e;}
    final byte[] owned=copy;independent(first,()->{if(owned!=null)wipe.accept(owned);},close);
  }
  private static void emit(String nonce,String status){System.out.println("JAVA_LOADER_RESULT "+nonce+" "+status+" profile="+ASSESSMENT.profile()+";layout="+ASSESSMENT.layout()+";scope=TEST_ONLY_DUMMY");if(System.out.checkError())throw new EvidenceFailure();}
}

class DeploymentCredentialLoaderNativeSafetyTest {
  private static BasicFileAttributes attributes(Object key){
    return new BasicFileAttributes(){
      public java.nio.file.attribute.FileTime lastModifiedTime(){return null;}public java.nio.file.attribute.FileTime lastAccessTime(){return null;}
      public java.nio.file.attribute.FileTime creationTime(){return null;}public boolean isRegularFile(){return true;}public boolean isDirectory(){return false;}
      public boolean isSymbolicLink(){return false;}public boolean isOther(){return false;}public long size(){return 32;}public Object fileKey(){return key;}
    };
  }
  @Test void actualBindRejectsReplacementHeldPathAndSecondIdentityBeforeSetter(){
    Path root=Path.of("C:\\new"),file=root.resolve("key");
    var identity=new WindowsDeploymentCustody.Identity(1,2,false,false,false,1,32);
    var owner=new DeploymentCredentialLoaderNativeTest.Owner();owner.create(root,p->{});owner.children[0]=file;
    owner.targets[1]=new DeploymentCredentialLoaderNativeTest.CreatedTarget(file,"original-key",false);
    int[] setters={0};
    owner.bind(1,file,false,identity,"\\\\?\\"+file,attributes("original-key"));setters[0]++;
    assertSame(identity,owner.identities[1]);
    for(int fault=0;fault<3;fault++){
      int selected=fault;
      assertThrows(IllegalStateException.class,()->{
        owner.bind(1,file,false,selected==2?new WindowsDeploymentCustody.Identity(1,9,false,false,false,1,32):identity,
            selected==1?"\\\\?\\C:\\redirect":"\\\\?\\"+file,attributes(selected==0?"replacement":"original-key"));
        setters[0]++;
      });
      assertSame(identity,owner.identities[1],"expected identity must never be relearned");
    }
    assertEquals(1,setters[0]);
  }
  @Test void actualClaimOwnerRetainsUnknownAndRejectsRepeatWithoutOverwrite(){
    var registry=new DeploymentCredentialLoaderNativeTest.Owner[1];
    var owner=DeploymentCredentialLoaderNativeTest.claimOwner(registry);owner.unknown=true;
    var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim();receipt.beginOperation();receipt.release(null);owner.probe=receipt;
    var weak=new java.lang.ref.WeakReference<>(owner);
    assertThrows(IllegalStateException.class,()->DeploymentCredentialLoaderNativeTest.claimOwner(registry));
    assertSame(owner,registry[0]);assertSame(receipt,registry[0].probe);owner=null;System.gc();
    assertSame(weak.get(),registry[0]);assertTrue(registry[0].unknown);
  }
  @Test void actualDisposableCopyFailurePathWipesAndClosesWithFatalPriority(){
    for(boolean comparisonFatal:new boolean[]{true,false}){
      byte[] disposable=new byte[32];Arrays.fill(disposable,(byte)7);int[] actions={0,0};
      Error fatal=new AssertionError("fatal");
      assertSame(fatal,assertThrows(Error.class,()->DeploymentCredentialLoaderNativeTest.verifyCopyThenClose(
          ()->disposable,copy->{if(comparisonFatal)throw fatal;throw new IllegalStateException("compare");},
          copy->{actions[0]++;Arrays.fill(copy,(byte)0);},
          ()->{actions[1]++;if(!comparisonFatal)throw fatal;throw new IllegalStateException("close");})));
      assertArrayEquals(new byte[32],disposable);assertArrayEquals(new int[]{1,1},actions);
    }
  }
  @Test void actualFinalStageEmissionFailureIsFailedAndCannotMaskBodyError(){
    String[] outcome={"BLOCKED_PREREQUISITE"};
    assertThrows(IllegalStateException.class,()->DeploymentCredentialLoaderNativeTest.finalStage(null,outcome,()->{throw new IllegalStateException("emit");}));
    assertEquals("FAILED",outcome[0]);
    Error fatal=new AssertionError("body");outcome[0]="BLOCKED_PREREQUISITE";
    assertSame(fatal,assertThrows(Error.class,()->DeploymentCredentialLoaderNativeTest.finalStage(fatal,outcome,()->{throw new IllegalStateException("emit");})));
    assertEquals("FAILED",outcome[0]);
    assertThrows(DeploymentCredentialLoaderNativeTest.EvidenceFailure.class,()->DeploymentCredentialLoaderNativeTest.identityStage(()->{throw new IllegalStateException("write");}));
  }
  @Test void ancestryBaselineMismatchAndUnknownOwnerCannotBeReplaced(){
    var owner=new DeploymentCredentialLoaderNativeTest.Owner();var id=new WindowsDeploymentCustody.Identity(1,2,true,false,false,1,0);
    owner.ancestryIdentity("mapping",id);
    assertThrows(IllegalArgumentException.class,()->owner.ancestryIdentity("redirect",id));
    assertThrows(IllegalArgumentException.class,()->owner.ancestryIdentity("mapping",new WindowsDeploymentCustody.Identity(2,3,true,false,false,1,0)));
  }
  @Test void creationAnchorRejectsNullKeyAndReplacementBeforeSetter(){
    Path path=Path.of("new");assertThrows(IllegalStateException.class,()->new DeploymentCredentialLoaderNativeTest.CreatedTarget(path,null,true));
    var anchor=new DeploymentCredentialLoaderNativeTest.CreatedTarget(path,"original",true);
    java.nio.file.attribute.BasicFileAttributes attributes=new java.nio.file.attribute.BasicFileAttributes(){
      public java.nio.file.attribute.FileTime lastModifiedTime(){return null;}public java.nio.file.attribute.FileTime lastAccessTime(){return null;}
      public java.nio.file.attribute.FileTime creationTime(){return null;}public boolean isRegularFile(){return false;}public boolean isDirectory(){return true;}
      public boolean isSymbolicLink(){return false;}public boolean isOther(){return false;}public long size(){return 0;}public Object fileKey(){return "replacement";}
    };
    assertThrows(IllegalStateException.class,()->anchor.verify(path,true,attributes));
  }
  @Test void creationFailureNeverOwnsOrPermitsSetter(){
    var owner=new DeploymentCredentialLoaderNativeTest.Owner();Path path=Path.of("existing");
    assertThrows(IllegalStateException.class,()->owner.create(path,p->{throw new IllegalStateException("exists");}));
    assertFalse(owner.created);assertNull(owner.root);
    assertThrows(IllegalStateException.class,()->owner.target(path,true,new WindowsDeploymentCustody.Identity(1,2,true,false,false,1,0)));
  }
  @Test void ancestryIncludesActualOutputAndRedirectFails(){
    Path output=Path.of("C:\\approved\\new-output");var chain=DeploymentCredentialLoaderNativeTest.ancestry(output);assertEquals(output,chain.get(chain.size()-1));
    assertThrows(IllegalArgumentException.class,()->DeploymentCredentialLoaderNativeTest.correlate(output,new WindowsDeploymentCustody.Identity(1,2,true,false,false,1,0),"\\\\?\\C:\\redirect"));
  }
  @Test void cleanupPriorityAttemptsAllSteps(){
    Error body=new AssertionError("body");int[] calls={0};
    assertSame(body,assertThrows(Error.class,()->DeploymentCredentialLoaderNativeTest.independent(body,
        ()->{calls[0]++;throw new IllegalStateException();},()->{calls[0]++;throw new AssertionError();},()->calls[0]++)));
    assertEquals(3,calls[0]);
  }
  @Test void predeclaredSidInputsAreCopiedAndNeverAdaptedToObservedPrincipal(){
    String sid="010100000000000512000000";
    var trust=new DeploymentCredentialLoaderNativeTest.Trust(sid,sid,sid);
    byte[][] parsed=DeploymentCredentialLoaderNativeTest.Trust.parse(sid);parsed[0][8]=99;
    var policy=trust.policy(Path.of("C:\\dummy"),HexFormat.of().parseHex(sid),new WindowsDeploymentCustody.Identity(1,2,true,false,false,1,0));
    assertTrue(policy.trusted(0,HexFormat.of().parseHex(sid)));
    assertFalse(policy.trusted(0,parsed[0]));
  }
  @Test void actualHandoffHelperWipesDisposableCopyAndNeverAcknowledges(){
    var slot=new DeploymentCredentialLoader.HandoffSlot();
    new DeploymentCredentialLoader(new DeploymentCredentialLoaderTest.Port()).load(DeploymentCredentialLoaderTest.policy(),slotReceipt(),slot);
    var value=slot.claim();DeploymentCredentialLoaderNativeTest.verifyCopyThenClose(value.credential(),slot);
    assertEquals("CLOSED_WIPED",slot.state());
  }
  private static DeploymentCredentialLoader.CustodyAttemptReceipt slotReceipt(){return new DeploymentCredentialLoader.CustodyAttemptReceipt();}
  @Test void missingPredeclaredTrustRejectsWithoutNative(){assertThrows(IllegalArgumentException.class,()->new DeploymentCredentialLoaderNativeTest.Trust(null,null,null));}
  @Test void setterTargetIsOnlyNewRootOrDirectChild(){var id=new WindowsDeploymentCustody.Identity(1,2,false,false,false,1,32);assertThrows(IllegalArgumentException.class,()->DeploymentCredentialLoaderNativeTest.requireNewTarget(Path.of("new"),Path.of("other/file"),false,id));}
  @Test void receiptUnknownNeverMeansPrerequisiteSuccess(){var owner=new DeploymentCredentialLoaderNativeTest.Owner();var receipt=new DeploymentCredentialLoader.CustodyAttemptReceipt();receipt.claim();receipt.beginOperation();receipt.release(null);assertThrows(IllegalStateException.class,()->owner.confirm(receipt));assertTrue(owner.unknown);}
}
