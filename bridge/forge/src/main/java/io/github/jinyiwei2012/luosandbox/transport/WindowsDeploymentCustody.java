package io.github.jinyiwei2012.luosandbox.transport;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/** Read-only snapshot backend. Native initialization exists only behind the explicit operation. */
final class WindowsDeploymentCustody implements DeploymentCredentialLoader.NativePort {
  record Identity(long volume, long index, boolean directory, boolean reparse, boolean deleting, int links, long size) {}
  interface Api {
    Object allocate(int bytes);
    Object open(String path, boolean directory);
    Identity identity(Object handle, Object query, Runnable check);
    String finalPath(Object handle, Object buffer, Runnable check);
    String mapping(String root, Object buffer, Runnable check);
    boolean queryDescriptor(Object handle, Object outputMemory);
    Object descriptorPointer(Object outputMemory);
    long extent(Object descriptor);
    byte[] descriptorBytes(Object descriptor, int size);
    int read(Object handle, byte[] output, int offset, int length, Object nativeBuffer, Runnable check);
    void clear(Object memory);
    void release(DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind kind, Object value);
  }
  private final Supplier<Api> factory;
  WindowsDeploymentCustody() { factory = NativeApi::new; }
  WindowsDeploymentCustody(Supplier<Api> factory) { this.factory = Objects.requireNonNull(factory); }

  public DeploymentCredentialLoader.Custody acquire(DeploymentCredentialLoader.TrustedDeploymentPolicy policy,
      DeploymentCredentialLoader.CustodyAttemptReceipt receipt) {
    return new Snapshot(policy, receipt, Objects.requireNonNull(factory.get()));
  }
  private static final class Snapshot implements DeploymentCredentialLoader.Custody {
    private final DeploymentCredentialLoader.TrustedDeploymentPolicy policy;
    private final DeploymentCredentialLoader.CustodyAttemptReceipt receipt;
    private final Api api;
    private final Object[] handles = new Object[38];
    private final Identity[] identities = new Identity[38];
    private final byte[][] descriptors = new byte[38][];
    private final int directories;
    private final Object query, readBuffer, names, descriptorOutput;
    private final String mapping;
    private boolean invalid;
    private String[] paths = new String[38];
    private Object begin() {
      synchronized (receipt) {
      receipt.acquiring();
       if (invalid) throw new DeploymentCredentialLoader.Unavailable();
       return receipt.beginOperation();
      }
    }
    private void current(Object token) {
      synchronized (receipt) {
      receipt.acquiring();
      receipt.check(token);
       if (invalid) throw new DeploymentCredentialLoader.Unavailable();
      }
    }
    Snapshot(DeploymentCredentialLoader.TrustedDeploymentPolicy policy,
        DeploymentCredentialLoader.CustodyAttemptReceipt receipt, Api api) {
      this.policy = policy; this.receipt = receipt; this.api = api;
      Object token = begin();
      try {
      query = memory(token, 4096, false); readBuffer = memory(token, 4096, false); names = memory(token, 520, true);
      descriptorOutput = memory(token, 4096, false);
      String drive = policy.root.substring(0, 3);
      mapping = api.mapping(drive, names, () -> current(token));
      current(token);
      if (!mapping.matches("\\\\Device\\\\HarddiskVolume[1-9][0-9]*")) throw new DeploymentCredentialLoader.Unavailable();
      String[] parts = policy.root.substring(3).split("\\\\");
      directories = parts.length + 1;
      String path = drive;
      for (int i = 0; i < directories; i++) {
        if (i > 0) path = path.endsWith("\\") ? path + parts[i - 1] : path + "\\" + parts[i - 1];
        acquire(token, i, path, true, i == directories - 1 ? -1 : -2);
      }
      for (DeploymentCredentialLoader.Purpose purpose : DeploymentCredentialLoader.Purpose.values()) {
        acquire(token, directories + purpose.ordinal(), policy.path(purpose), false, purpose.ordinal());
      }
      current(token);
      } finally { receipt.endOperation(token); }
      validate();
    }
    private Object memory(Object token, int size, boolean wchar) {
      var kind = wchar ? DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.WCHAR_MEMORY
          : DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.QUERY_MEMORY;
      var record = receipt.reserve(token, kind, api);
      Object returned;
      try { returned = api.allocate(size); }
      catch (Throwable failure) { receipt.finishEmptyFailure(token, record); throw failure; }
      return receipt.bindReturned(token, record, returned);
    }
    private void acquire(Object token, int index, String path, boolean directory, int owner) {
      var record = receipt.reserve(token, DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE, api);
      Object returned;
      try { returned = api.open(path, directory); }
      catch (Throwable failure) { receipt.finishEmptyFailure(token, record); throw failure; }
      handles[index] = receipt.bindReturned(token, record, returned); paths[index] = path;
      current(token);
      Identity identity = api.identity(handles[index], query, () -> current(token));
      current(token);
      check(identity, directory, owner);
      checkPath(api.finalPath(handles[index], names, () -> current(token)), path);
      current(token);
      for (int i = 0; i < index; i++) if (identities[i].volume == identity.volume && identities[i].index == identity.index)
        throw new DeploymentCredentialLoader.Unavailable();
      identities[index] = identity;
      descriptors[index] = inspect(token, handles[index], directory, owner);
    }
    private void check(Identity value, boolean directory, int owner) {
      if (value.volume != policy.volume || value.index == 0 || value.directory != directory || value.reparse
          || value.deleting || !directory && value.links != 1 || owner == -1 && value.index != policy.rootIndex)
        throw new DeploymentCredentialLoader.Unavailable();
    }
    private byte[] inspect(Object token, Object handle, boolean directory, int owner) {
      var allocation = receipt.reserve(token, DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.DESCRIPTOR, api);
      boolean success = false; Throwable first = null; Object pointer = null; boolean ambiguous = false;
      try { success = api.queryDescriptor(handle, descriptorOutput); }
      catch (Throwable failure) { first = failure; }
      try { current(token); pointer = api.descriptorPointer(descriptorOutput); }
      catch (Throwable failure) { ambiguous = true; if (first == null || !(first instanceof Error) && failure instanceof Error) first = failure; }
      try { receipt.reconcileDescriptor(token, allocation, success && first == null, pointer, ambiguous); }
      catch (Throwable failure) { if (first == null || !(first instanceof Error) && failure instanceof Error) first = failure; }
      if (first instanceof Error error) throw error;
      if (first != null) throw new DeploymentCredentialLoader.Unavailable();
      current(token);
      long extent = api.extent(pointer);
      current(token);
      if (extent < 20 || extent > 4096) throw new DeploymentCredentialLoader.Unavailable();
      byte[] raw = api.descriptorBytes(pointer, (int) extent);
      current(token);
      assess(raw, policy, directory, owner);
      receipt.closeOwned(token, allocation); return raw;
    }
    public void validate() {
      Object token = begin();
      try {
        current(token);
        if (!mapping.equals(api.mapping(policy.root.substring(0, 3), names, () -> current(token)))) throw new DeploymentCredentialLoader.Unavailable();
        current(token);
        for (int i = 0; i < directories + 5; i++) {
          Identity observed = api.identity(handles[i], query, () -> current(token));
          current(token);
          int owner = i < directories - 1 ? -2 : i == directories - 1 ? -1 : i - directories;
          check(observed, i < directories, owner);
          checkPath(api.finalPath(handles[i], names, () -> current(token)), paths[i]);
          current(token);
          if (!observed.equals(identities[i]) || !Arrays.equals(descriptors[i], inspect(token, handles[i], i < directories, owner)))
            throw new DeploymentCredentialLoader.Unavailable();
        }
      } catch (Throwable failure) { invalid = true; throw failure; }
      finally { receipt.endOperation(token); }
    }
    public DeploymentCredentialLoader.HeldFile file(DeploymentCredentialLoader.Purpose purpose) {
      if (invalid) throw new DeploymentCredentialLoader.Unavailable();
      Object handle = handles[directories + purpose.ordinal()];
      return (buffer, offset, length) -> {
        Object token = begin();
        try {
          receipt.acquiring();
          int count = api.read(handle, buffer, offset, Math.min(length, 4096), readBuffer, () -> current(token));
          current(token); return count;
        }
        catch (Throwable failure) { invalid = true; throw failure; }
        finally { receipt.endOperation(token); }
      };
    }
    private static void checkPath(String observed, String expected) {
      if (!observed.equalsIgnoreCase("\\\\?\\" + expected)) throw new DeploymentCredentialLoader.Unavailable();
    }
  }

  /** Self-relative descriptor parsing: establish every range before accessing its contents. */
  enum AssessmentReason {
    ACCEPTED, DESCRIPTOR_ENVELOPE, DESCRIPTOR_REVISION, DESCRIPTOR_CONTROL, DESCRIPTOR_OFFSETS,
    SID_ENCODING, OWNER_POLICY, ACL_REVISION, ACL_EXTENT, ACE_COUNT, ACE_GRAMMAR, ACCESS_MASK,
    CONTENT_WRITER_POLICY, SECURITY_MUTATOR_POLICY, NAMESPACE_MUTATOR_POLICY, CREDENTIAL_READER_POLICY
  }
  enum AssessmentProfile {
    STRICT_SNAPSHOT_V1,
    PROTECTED_AUTO_INHERITED_SNAPSHOT_V1;
    boolean acceptsControl(int control) {
      return control == 0x9004 || this == PROTECTED_AUTO_INHERITED_SNAPSHOT_V1 && control == 0x9404;
    }
  }
  private static final class Rejection extends RuntimeException {
    final AssessmentReason reason;
    Rejection(AssessmentReason reason) { super(null, null, false, false); this.reason = reason; }
  }
  private static Rejection reject(AssessmentReason reason) { return new Rejection(reason); }
  static void assess(byte[] raw, DeploymentCredentialLoader.TrustedDeploymentPolicy policy, boolean directory, int owner) {
    if (assessReason(raw, policy, directory, owner) != AssessmentReason.ACCEPTED) throw new DeploymentCredentialLoader.Unavailable();
  }
  static AssessmentReason assessReason(byte[] raw, DeploymentCredentialLoader.TrustedDeploymentPolicy policy, boolean directory, int owner) {
    return assessReason(raw, policy, directory, owner, AssessmentProfile.STRICT_SNAPSHOT_V1);
  }
  static AssessmentReason assessReason(byte[] raw, DeploymentCredentialLoader.TrustedDeploymentPolicy policy, boolean directory, int owner,
      AssessmentProfile profile) {
    Objects.requireNonNull(profile);
    try { parseAssessment(raw, policy, directory, owner, profile); return AssessmentReason.ACCEPTED; }
    catch (Rejection rejected) { return rejected.reason; }
  }
  private static void parseAssessment(byte[] raw, DeploymentCredentialLoader.TrustedDeploymentPolicy policy, boolean directory, int owner,
      AssessmentProfile profile) {
    if (raw == null || raw.length < 20 || raw.length > 4096) throw reject(AssessmentReason.DESCRIPTOR_ENVELOPE);
    if (raw[0] != 1) throw reject(AssessmentReason.DESCRIPTOR_REVISION);
    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    int control = Short.toUnsignedInt(b.getShort(2));
    if (!profile.acceptsControl(control) || raw[1] != 0 || b.getInt(8) != 0 || b.getInt(12) != 0) throw reject(AssessmentReason.DESCRIPTOR_CONTROL);
    int ownerOffset = b.getInt(4);
    byte[] sid = sid(raw, ownerOffset);
    if (!policy.trusted(1, sid) || owner != -2 && !Arrays.equals(sid, policy.owner(owner))) throw reject(AssessmentReason.OWNER_POLICY);
    int acl = b.getInt(16); range(raw, acl, 8, AssessmentReason.DESCRIPTOR_OFFSETS);
    if (raw[acl] != 2 || raw[acl + 1] != 0 || b.getShort(acl + 6) != 0
        || ownerOffset != 20 || acl != ownerOffset + sid.length) throw reject(AssessmentReason.ACL_REVISION);
    int length = Short.toUnsignedInt(b.getShort(acl + 2)), count = Short.toUnsignedInt(b.getShort(acl + 4));
    range(raw, acl, length, AssessmentReason.ACL_EXTENT);
    if (length < 8) throw reject(AssessmentReason.ACL_EXTENT);
    if (count > 128) throw reject(AssessmentReason.ACE_COUNT);
    if (acl + length != raw.length) throw reject(AssessmentReason.ACL_EXTENT);
    int cursor = acl + 8;
    for (int i = 0; i < count; i++) {
      range(raw, cursor, 8, AssessmentReason.ACE_GRAMMAR); int size = Short.toUnsignedInt(b.getShort(cursor + 2));
      if (raw[cursor] != 0 || raw[cursor + 1] != 0 || size < 20 || cursor + size > acl + length) throw reject(AssessmentReason.ACE_GRAMMAR);
      byte[] principal = sid(raw, cursor + 8);
      if (principal.length != size - 8 || principal[7] == 3) throw reject(AssessmentReason.ACE_GRAMMAR);
      int mask = b.getInt(cursor + 4);
      int[] generic = {0x80000000, 0x40000000, 0x20000000, 0x10000000};
      int[] specific = {0x120089, 0x120116, 0x1200a0, 0x1f01ff};
      for (int j = 0; j < 4; j++) if ((mask & generic[j]) != 0) mask = mask & ~generic[j] | specific[j];
      if ((mask & ~0x1f01ff) != 0 || !directory && (mask & 0x40) != 0) throw reject(AssessmentReason.ACCESS_MASK);
      require(policy, principal, mask & (directory ? 0x110 : 0x116), 0);
      require(policy, principal, mask & 0xc0000, 1);
      require(policy, principal, mask & (directory ? 0x10046 : 0x10000), 2);
      if (owner == 4) require(policy, principal, mask & 1, 3);
      cursor += size;
    }
    if (cursor != acl + length) throw reject(AssessmentReason.ACL_EXTENT);
  }
  private static void require(DeploymentCredentialLoader.TrustedDeploymentPolicy p, byte[] sid, int touched, int role) {
    if (touched != 0 && !p.trusted(role, sid)) throw reject(switch (role) {
      case 0 -> AssessmentReason.CONTENT_WRITER_POLICY; case 1 -> AssessmentReason.SECURITY_MUTATOR_POLICY;
      case 2 -> AssessmentReason.NAMESPACE_MUTATOR_POLICY; case 3 -> AssessmentReason.CREDENTIAL_READER_POLICY;
      default -> throw new AssertionError();
    });
  }
  private static byte[] sid(byte[] raw, int offset) {
    range(raw, offset, 8, AssessmentReason.SID_ENCODING); int size = 8 + (raw[offset + 1] & 255) * 4;
    if (raw[offset] != 1 || size < 12 || size > 68) throw reject(AssessmentReason.SID_ENCODING);
    range(raw, offset, size, AssessmentReason.SID_ENCODING); return Arrays.copyOfRange(raw, offset, offset + size);
  }
  private static void range(byte[] raw, int offset, int count, AssessmentReason reason) {
    if (offset < 20 || count < 0 || offset > raw.length - count) throw reject(reason);
  }

  interface KernelW extends StdCallLibrary {
    Pointer CreateFileW(WString path, int access, int share, Pointer attributes, int disposition, int flags, Pointer template);
    boolean GetFileInformationByHandle(Pointer handle, Pointer info);
    boolean GetFileInformationByHandleEx(Pointer handle, int kind, Pointer info, int length);
    int GetFileType(Pointer handle);
    int GetFinalPathNameByHandleW(Pointer handle, Pointer name, int length, int flags);
    boolean GetVolumeInformationByHandleW(Pointer handle, Pointer name, int nameSize, Pointer serial, Pointer max, Pointer flags, Pointer fs, int fsSize);
    int QueryDosDeviceW(WString drive, Pointer names, int length);
    int GetDriveTypeW(WString root);
    boolean ReadFile(Pointer handle, Pointer buffer, int length, Pointer count, Pointer overlapped);
    long LocalSize(Pointer value);
    Pointer LocalFree(Pointer value);
    boolean CloseHandle(Pointer handle);
  }
  interface SecurityW extends StdCallLibrary {
    int GetSecurityInfo(Pointer handle, int kind, int flags, Pointer owner, Pointer group, Pointer dacl, Pointer sacl, Pointer descriptor);
  }
  interface BoundedBuffer {
    void clear();
    int integer(int offset);
    byte octet(int offset);
    short word(int offset);
    void copy(byte[] target, int offset, int count);
  }
  interface NativeSurface {
    int fileType(Object handle);
    boolean information(Object handle, Object buffer);
    boolean standard(Object handle, Object buffer);
    boolean volume(Object handle, Object buffer);
    boolean read(Object handle, Object buffer, int count);
    default int finalPath(Object handle, Object buffer, int capacity) { throw new DeploymentCredentialLoader.Unavailable(); }
    default int mapping(String drive, Object buffer, int capacity) { throw new DeploymentCredentialLoader.Unavailable(); }
    default int driveType(String root) { throw new DeploymentCredentialLoader.Unavailable(); }
    default boolean descriptor(Object handle, Object output) { throw new DeploymentCredentialLoader.Unavailable(); }
    default Object pointer(Object output) { throw new DeploymentCredentialLoader.Unavailable(); }
    default long extent(Object pointer) { throw new DeploymentCredentialLoader.Unavailable(); }
    default byte[] bytes(Object pointer, int count) { throw new DeploymentCredentialLoader.Unavailable(); }
  }
  static final class NativeApi implements Api {
    private final KernelW kernel;
    private final SecurityW security;
    private final NativeSurface surface;
    NativeApi() {
      kernel = Native.load("kernel32", KernelW.class);
      security = Native.load("advapi32", SecurityW.class);
      surface = new NativeSurface() {
        public int fileType(Object h) { return kernel.GetFileType((Pointer) h); }
        public boolean information(Object h,Object b) { return kernel.GetFileInformationByHandle((Pointer) h,(Memory)b); }
        public boolean standard(Object h,Object b) { return kernel.GetFileInformationByHandleEx((Pointer)h,1,(Memory)b,24); }
        public boolean volume(Object h,Object b) { return kernel.GetVolumeInformationByHandleW((Pointer)h,null,0,null,null,null,(Memory)b,16); }
        public boolean read(Object h,Object b,int count) { return kernel.ReadFile((Pointer)h,(Memory)b,count,((Memory)b).share(4092),null); }
        public int finalPath(Object h,Object b,int n) { return kernel.GetFinalPathNameByHandleW((Pointer)h,(Memory)b,n,0); }
        public int mapping(String d,Object b,int n) { return kernel.QueryDosDeviceW(new WString(d),(Memory)b,n); }
        public int driveType(String r) { return kernel.GetDriveTypeW(new WString(r)); }
        public boolean descriptor(Object h,Object b) { return security.GetSecurityInfo((Pointer)h,1,5,null,null,null,null,(Memory)b)==0; }
        public Object pointer(Object b) { return ((Memory)b).getPointer(0); }
        public long extent(Object p) { return kernel.LocalSize((Pointer)p); }
        public byte[] bytes(Object p,int n) { return ((Pointer)p).getByteArray(0,n); }
      };
    }
    NativeApi(NativeSurface surface) { this.surface = Objects.requireNonNull(surface); kernel = null; security = null; }
    private static BoundedBuffer buffer(Object value) {
      if (value instanceof BoundedBuffer bounded) return bounded;
      Memory memory = (Memory) value;
      return new BoundedBuffer() {
        public void clear() { memory.clear(); }
        public int integer(int offset) { return memory.getInt(offset); }
        public byte octet(int offset) { return memory.getByte(offset); }
        public short word(int offset) { return memory.getShort(offset); }
        public void copy(byte[] target,int offset,int count) { memory.read(0,target,offset,count); }
      };
    }
    public Object allocate(int bytes) {
      // A throwing JNA constructor exposes no returned allocation; provider-internal recovery is not claimed.
      return new Memory(bytes);
    }
    public Object open(String path, boolean directory) {
      Pointer handle = kernel.CreateFileW(new WString(path), directory ? 0x20080 : 0x80020080,
          directory ? 3 : 1, null, 3, directory ? 0x2200000 : 0x200000, null);
      if (handle == null || Pointer.nativeValue(handle) == -1) throw new DeploymentCredentialLoader.Unavailable();
      return handle;
    }
    public Identity identity(Object handle, Object query, Runnable check) {
      BoundedBuffer q = buffer(query); q.clear();
      int type = surface.fileType(handle); check.run();
      if (type != 1) throw new DeploymentCredentialLoader.Unavailable();
      if (!surface.information(handle, query)) throw new DeploymentCredentialLoader.Unavailable();
      check.run();
      long volume = Integer.toUnsignedLong(q.integer(28));
      long index = Integer.toUnsignedLong(q.integer(44)) << 32 | Integer.toUnsignedLong(q.integer(48));
      int links = q.integer(40), attributes = q.integer(0);
      long size = Integer.toUnsignedLong(q.integer(32)) << 32 | Integer.toUnsignedLong(q.integer(36));
      q.clear();
      if (!surface.standard(handle, query)) throw new DeploymentCredentialLoader.Unavailable();
      check.run();
      boolean deleting = q.octet(20) != 0, directory = q.octet(21) != 0;
      q.clear();
      boolean volumeOk = surface.volume(handle, query);
      check.run();
      if (!volumeOk || q.word(0) != 'N' || q.word(2) != 'T' || q.word(4) != 'F' || q.word(6) != 'S' || q.word(8) != 0)
        throw new DeploymentCredentialLoader.Unavailable();
      return new Identity(volume, index, directory, (attributes & 0x400) != 0, deleting, links, size);
    }
    public String finalPath(Object handle, Object buffer, Runnable check) {
      BoundedBuffer q = buffer(buffer); q.clear();
      int count = surface.finalPath(handle, buffer, 260);
      check.run();
      if (count <= 0 || count >= 260 || q.word(count * 2) != 0) throw new DeploymentCredentialLoader.Unavailable();
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < count; i++) { char c = (char) q.word(i * 2); if (c == 0) throw new DeploymentCredentialLoader.Unavailable(); text.append(c); }
      return text.toString();
    }
    public String mapping(String root, Object buffer, Runnable check) {
      BoundedBuffer q = buffer(buffer); q.clear();
      int count = surface.mapping(root.substring(0, 2), buffer, 260);
      check.run();
      if (count < 3 || count >= 260) throw new DeploymentCredentialLoader.Unavailable();
      int drive = surface.driveType(root); check.run();
      if (drive != 3) throw new DeploymentCredentialLoader.Unavailable();
      StringBuilder value = new StringBuilder();
      for (int i = 0; i < count - 2; i++) { char c = (char) q.word(i * 2); if (c == 0) throw new DeploymentCredentialLoader.Unavailable(); value.append(c); }
      if (q.word((count - 1) * 2) != 0 || q.word((count - 2) * 2) != 0) throw new DeploymentCredentialLoader.Unavailable();
      return value.toString();
    }
    public boolean queryDescriptor(Object handle, Object outputMemory) {
      // Output storage is installed in the caller-owned record before the native query.
      buffer(outputMemory).clear();
      return surface.descriptor(handle, outputMemory);
    }
    public Object descriptorPointer(Object outputMemory) { return surface.pointer(outputMemory); }
    public long extent(Object descriptor) { return surface.extent(descriptor); }
    public byte[] descriptorBytes(Object descriptor, int size) { return surface.bytes(descriptor,size); }
    public int read(Object handle, byte[] output, int offset, int length, Object nativeBuffer, Runnable check) {
      BoundedBuffer memory = buffer(nativeBuffer);
      // Keep DWORD result inside the same bounded caller-owned allocation.
      int wanted = Math.min(length, 4092); memory.clear();
      if (!surface.read(handle, nativeBuffer, wanted)) throw new DeploymentCredentialLoader.Unavailable();
      check.run();
      int count = memory.integer(4092);
      if (count < 0 || count > wanted) throw new DeploymentCredentialLoader.Unavailable();
      if (count == 0) return -1;
      memory.copy(output, offset, count); memory.clear(); return count;
    }
    public void clear(Object memory) { ((Memory) memory).clear(); }
    public void release(DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind kind, Object value) {
      switch (kind) {
        case HANDLE -> { if (!kernel.CloseHandle((Pointer) value)) throw new DeploymentCredentialLoader.Unavailable(); }
        case DESCRIPTOR -> { if (kernel.LocalFree((Pointer) value) != null) throw new DeploymentCredentialLoader.Unavailable(); }
        default -> ((Memory) value).close();
      }
    }
  }
}
