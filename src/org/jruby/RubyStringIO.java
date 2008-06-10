/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006 Ola Bini <ola@ologix.com>
 * Copyright (C) 2006 Ryan Bell <ryan.l.bell@gmail.com>
 * Copyright (C) 2007 Thomas E Enebo <enebo@acm.org>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import java.util.ArrayList;
import java.util.List;

import org.jruby.anno.FrameField;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.MethodIndex;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.io.InvalidValueException;
import org.jruby.util.io.ModeFlags;
import org.jruby.util.io.Stream;

@JRubyClass(name="StringIO")
public class RubyStringIO extends RubyObject {
    private static ObjectAllocator STRINGIO_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyStringIO(runtime, klass);
        }
    };
    
    public static RubyClass createStringIOClass(final Ruby runtime) {
        RubyClass stringIOClass = runtime.defineClass("StringIO", runtime.getObject(), STRINGIO_ALLOCATOR);
        
        stringIOClass.defineAnnotatedMethods(RubyStringIO.class);

        return stringIOClass;
    }

    @JRubyMethod(name = "open", optional = 2, frame = true, meta = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        RubyStringIO strio = (RubyStringIO)((RubyClass)recv).newInstance(context, args, Block.NULL_BLOCK);
        IRubyObject val = strio;
        
        if (block.isGiven()) {
            try {
                val = block.yield(context, strio);
            } finally {
                strio.close();
            }
        }
        return val;
    }

    protected RubyStringIO(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    private long pos = 0L;
    private int lineno = 0;
    private boolean eof = false;
    private RubyString internal;

    // Has read/write been closed or is it still open for business
    private boolean closedRead = false;
    private boolean closedWrite = false;

    // Support IO modes that this object was opened with
    ModeFlags modes;
    
    private void initializeModes(Object modeArgument) {
        try {        
            if (modeArgument == null) {
                modes = new ModeFlags(RubyIO.getIOModesIntFromString(getRuntime(), "r+"));            
            } else if (modeArgument instanceof Long) {
                modes = new ModeFlags(((Long)modeArgument).longValue());
            } else {
                modes = new ModeFlags(RubyIO.getIOModesIntFromString(getRuntime(), (String) modeArgument));            
            }
        } catch (InvalidValueException e) {
            throw getRuntime().newErrnoEINVALError();
        }
        setupModes();
    }

    @JRubyMethod(name = "initialize", optional = 2, frame = true, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(IRubyObject[] args, Block unusedBlock) {
        Object modeArgument = null;
        switch (args.length) {
            case 0:
                internal = RubyString.newEmptyString(getRuntime());
                modeArgument = "r+";
                break;
            case 1:
                internal = args[0].convertToString();
                modeArgument = internal.isFrozen() ? "r" : "r+";
                break;
            case 2:
                internal = args[0].convertToString();
                if (args[1] instanceof RubyFixnum) {
                    modeArgument = RubyFixnum.fix2long(args[1]);
                } else {
                    modeArgument = args[1].convertToString().toString();
                }
                break;
        }

        initializeModes(modeArgument);

        if (modes.isWritable() && internal.isFrozen()) {
            throw getRuntime().newErrnoEACCESError("Permission denied");
        }

        if (modes.isTruncate()) {
            internal.modifyCheck();
            internal.empty();
        }

        return this;
    }

    @JRubyMethod(name = "<<", required = 1)
    public IRubyObject append(ThreadContext context, IRubyObject obj) {
        checkWritable();
        checkFrozen();

        RubyString val = obj.asString();
        internal.modify();
        if (modes.isAppendable()) {
            internal.getByteList().append(val.getByteList());
        } else {
            int left = internal.getByteList().length()-(int)pos;
            internal.getByteList().replace((int)pos,Math.min(val.getByteList().length(),left),val.getByteList());
            pos += val.getByteList().length();
        }

        if (val.isTaint()) {
            internal.setTaint(true);
        }

        return this;
    }

    @JRubyMethod(name = "binmode")
    public IRubyObject binmode() {
        return this;
    }
    
    @JRubyMethod(name = "close")
    public IRubyObject close() {
        checkInitialized();
        if (closedRead && closedWrite) {
            throw getRuntime().newIOError("closed stream");
        }

        closedRead = true;
        closedWrite = true;
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p() {
        checkInitialized();
        return getRuntime().newBoolean(closedRead && closedWrite);
    }

    @JRubyMethod(name = "close_read")
    public IRubyObject close_read() {
        checkReadable();
        closedRead = true;
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "closed_read?")
    public IRubyObject closed_read_p() {
        checkInitialized();
        return getRuntime().newBoolean(closedRead);
    }

    @JRubyMethod(name = "close_write")
    public IRubyObject close_write() {
        checkWritable();
        closedWrite = true;
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "closed_write?")
    public IRubyObject closed_write_p() {
        checkInitialized();
        return getRuntime().newBoolean(closedWrite);
    }

    @JRubyMethod(name = "each", optional = 1, frame = true, writes = FrameField.LASTLINE)
    public IRubyObject each(ThreadContext context, IRubyObject[] args, Block block) {
        IRubyObject line = gets(context, args);
       
        while (!line.isNil()) {
            block.yield(context, line);
            line = gets(context, args);
        }
       
        return this;
    }

    @JRubyMethod(name = "each_byte", frame = true)
    public IRubyObject each_byte(ThreadContext context, Block block) {
        checkReadable();
        
        RubyString.newString(getRuntime(),new ByteList(internal.getByteList(), (int)pos, internal.getByteList().length())).each_byte(context, block);
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "each_line", optional = 1, frame = true)
    public IRubyObject each_line(ThreadContext context, IRubyObject[] args, Block block) {
        return each(context, args, block);
    }

    @JRubyMethod(name = {"eof", "eof?"})
    public IRubyObject eof() {
        return (pos >= internal.getByteList().length() || eof) ? getRuntime().getTrue() : getRuntime().getFalse();
    }

    @JRubyMethod(name = "fcntl")
    public IRubyObject fcntl() {
        throw getRuntime().newNotImplementedError("fcntl not implemented");
    }

    @JRubyMethod(name = "fileno")
    public IRubyObject fileno() {
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "flush")
    public IRubyObject flush() {
        return this;
    }

    @JRubyMethod(name = "fsync")
    public IRubyObject fsync() {
        return RubyFixnum.zero(getRuntime());
    }

    @JRubyMethod(name = "getc")
    public IRubyObject getc() {
        if (pos >= internal.getByteList().length()) {
            return getRuntime().getNil();
        }
        return getRuntime().newFixnum(internal.getByteList().get((int)pos++) & 0xFF);
    }

    private IRubyObject internalGets(ThreadContext context, IRubyObject[] args) {
        Ruby runtime = context.getRuntime();

        if (pos < internal.getByteList().realSize && !eof) {
            boolean isParagraph = false;

            ByteList sep;
            if (args.length > 0) {
                if (args[0].isNil()) {
                    ByteList buf = internal.getByteList().makeShared(
                            (int)pos, internal.getByteList().realSize - (int)pos);
                    pos += buf.realSize;
                    return RubyString.newString(runtime, buf);
                }
                sep = args[0].convertToString().getByteList();
                if (sep.realSize == 0) {
                    isParagraph = true;
                    sep = Stream.PARAGRAPH_SEPARATOR;
                }
            } else {
                sep = ((RubyString)runtime.getGlobalVariables().get("$/")).getByteList();
            }

            ByteList ss = internal.getByteList();

            if (isParagraph) {
                swallowLF(ss);
                if (pos == ss.realSize) {
                    return runtime.getNil();
                }
            }

            int ix = ss.indexOf(sep, (int)pos);

            ByteList add;
            if (-1 == ix) {
                ix = internal.getByteList().realSize;
                add = new ByteList(new byte[0], false);
            } else {
                add = isParagraph? NEWLINE : sep;
            }

            ByteList line = internal.getByteList().makeShared((int)pos, ix - (int)pos);
            line.unshare();
            line.append(add);
            line.invalidate();
            pos = ix + add.realSize;
            lineno++;

            return RubyString.newString(runtime,line);
        }
        return runtime.getNil();
    }

    private void swallowLF(ByteList list) {
        while (pos < list.realSize) {
            if (list.get((int)pos) == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    @JRubyMethod(name = "gets", optional = 1, writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject[] args) {
        checkReadable();

        IRubyObject result = internalGets(context, args);
        context.getCurrentFrame().setLastLine(result);

        return result;
    }

    @JRubyMethod(name = "isatty")
    public IRubyObject isatty() {
        return getRuntime().getFalse();
    }

    @JRubyMethod(name = "tty?")
    public IRubyObject tty_p() {
        return getRuntime().getFalse();
    }

    @JRubyMethod(name = "length")
    public IRubyObject length() {
        return getRuntime().newFixnum(internal.getByteList().length());
    }

    @JRubyMethod(name = "lineno")
    public IRubyObject lineno() {
        return getRuntime().newFixnum(lineno);
    }

    @JRubyMethod(name = "lineno=", required = 1)
    public IRubyObject set_lineno(IRubyObject arg) {
        lineno = RubyNumeric.fix2int(arg);
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "path")
    public IRubyObject path() {
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "pid")
    public IRubyObject pid() {
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "pos")
    public IRubyObject pos() {
        return getRuntime().newFixnum(pos);
    }

    @JRubyMethod(name = "tell")
    public IRubyObject tell() {
        return getRuntime().newFixnum(pos);
    }

    @JRubyMethod(name = "pos=", required = 1)
    public IRubyObject set_pos(IRubyObject arg) {
        pos = RubyNumeric.fix2int(arg);
        if (pos < 0) {
            throw getRuntime().newErrnoEINVALError("Invalid argument");
        }
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "print", rest = true)
    public IRubyObject print(ThreadContext context, IRubyObject[] args) {
        if (args.length != 0) {
            for (int i=0,j=args.length;i<j;i++) {
                append(context, args[i]);
            }
        }
        IRubyObject sep = getRuntime().getGlobalVariables().get("$\\");
        if (!sep.isNil()) {
            append(context, sep);
        }
        return getRuntime().getNil();
    }
    
    @JRubyMethod(name = "printf", required = 1, rest = true)
    public IRubyObject printf(ThreadContext context, IRubyObject[] args) {
        append(context, RubyKernel.sprintf(this,args));
        return getRuntime().getNil();
    }

    @JRubyMethod(name = "putc", required = 1)
    public IRubyObject putc(IRubyObject obj) {
        checkWritable();
        byte c = RubyNumeric.num2chr(obj);
        checkFrozen();

        internal.modify();
        if (modes.isAppendable()) {
            pos = internal.getByteList().length();
            internal.getByteList().append(c);
        } else {
            if (pos >= internal.getByteList().length()) {
                internal.getByteList().append(c);
            } else {
                internal.getByteList().set((int) pos, c);
            }
            pos++;
        }

        return obj;
    }
    
    public static final ByteList NEWLINE = ByteList.create("\n");

    @JRubyMethod(name = "puts", rest = true)
    public IRubyObject puts(ThreadContext context, IRubyObject[] args) {
        checkWritable();
        
        // FIXME: the code below is a copy of RubyIO.puts,
        // and we should avoid copy-paste.
        
        if (args.length == 0) {
            callMethod(context, "write", RubyString.newStringShared(getRuntime(), NEWLINE));
            return getRuntime().getNil();
        }

        for (int i = 0; i < args.length; i++) {
            String line;
            
            if (args[i].isNil()) {
                line = "nil";
            } else if (getRuntime().isInspecting(args[i])) {
                line = "[...]";
            } else if (args[i] instanceof RubyArray) {
                inspectPuts(context, (RubyArray) args[i]);
                continue;
            } else {
                line = args[i].toString();
            }
            
            callMethod(context, "write", getRuntime().newString(line));
            
            if (!line.endsWith("\n")) {
                callMethod(context, "write", RubyString.newStringShared(getRuntime(), NEWLINE));
            }
        }
        return getRuntime().getNil();
    }

    private IRubyObject inspectPuts(ThreadContext context, RubyArray array) {
        try {
            getRuntime().registerInspecting(array);
            return puts(context, array.toJavaArray());
        } finally {
            getRuntime().unregisterInspecting(array);
        }
    }

    @SuppressWarnings("fallthrough")
    @JRubyMethod(name = "read", optional = 2)
    public IRubyObject read(IRubyObject[] args) {
        checkReadable();

        ByteList buf = null;
        int length = 0;
        int oldLength = 0;
        RubyString originalString = null;
        
        switch (args.length) {
        case 2:
            originalString = args[1].convertToString();
            // must let original string know we're modifying, so shared buffers aren't damaged
            originalString.modify();
            buf = originalString.getByteList();
        case 1:
            if (!args[0].isNil()) {
                length = RubyNumeric.fix2int(args[0]);
                oldLength = length;
                
                if (length < 0) {
                    throw getRuntime().newArgumentError("negative length " + length + " given");
                }
                if (length > 0 && pos >= internal.getByteList().length()) {
                    eof = true;
                    if (buf != null) buf.realSize = 0;
                    return getRuntime().getNil();
                } else if (eof) {
                    if (buf != null) buf.realSize = 0;
                    return getRuntime().getNil();
                }
                break;
            }
        case 0:
            oldLength = -1;
            length = internal.getByteList().length();
            
            if (length <= pos) {
                eof = true;
                if (buf == null) {
                    buf = new ByteList();
                } else {
                    buf.realSize = 0;
                }
                
                return getRuntime().newString(buf);
            } else {
                length -= pos;
            }
            break;
        default:
            getRuntime().newArgumentError(args.length, 0);
        }
         
        if (buf == null) {
            int internalLength = internal.getByteList().length();
         
            if (internalLength > 0) {
                if (internalLength >= pos + length) {
                    buf = new ByteList(internal.getByteList(), (int) pos, length);  
                } else {
                    int rest = (int) (internal.getByteList().length() - pos);
                    
                    if (length > rest) length = rest;
                    buf = new ByteList(internal.getByteList(), (int) pos, length);
                }
            }
        } else {
            int rest = (int) (internal.getByteList().length() - pos);
            
            if (length > rest) length = rest;

            // Yow...this is ugly
            buf.realSize = length;
            buf.replace(0, length, internal.getByteList().bytes, (int) pos, length);
        }
        
        if (buf == null) {
            if (!eof) buf = new ByteList();
            length = 0;
        } else {
            length = buf.length();
            pos += length;
        }
        
        if (oldLength < 0 || oldLength > length) eof = true;
        
        return originalString != null ? originalString : getRuntime().newString(buf);
    }

    @JRubyMethod(name = "readchar")
    public IRubyObject readchar() {
        IRubyObject c = getc();
        
        if (c.isNil()) throw getRuntime().newEOFError();
        
        return c;
    }

    @JRubyMethod(name = "readline", optional = 1, writes = FrameField.LASTLINE)
    public IRubyObject readline(ThreadContext context, IRubyObject[] args) {
        IRubyObject line = gets(context, args);
        
        if (line.isNil()) throw getRuntime().newEOFError();
        
        return line;
    }
    
    @JRubyMethod(name = "readlines", optional = 1, writes = FrameField.LASTLINE)
    public IRubyObject readlines(ThreadContext context, IRubyObject[] arg) {
        checkReadable();

        List<IRubyObject> lns = new ArrayList<IRubyObject>();
        while (!(pos >= internal.getByteList().length() || eof)) {
            IRubyObject line = gets(context, arg);
            if (line.isNil()) {
                break;
            }
            lns.add(line);
        }

        return getRuntime().newArray(lns);
    }

    @JRubyMethod(name = "reopen", required = 1, optional = 1)
    public IRubyObject reopen(IRubyObject[] args) {
        IRubyObject str = args[0];
        if (str instanceof RubyStringIO) {
            pos = ((RubyStringIO)str).pos;
            lineno = ((RubyStringIO)str).lineno;
            eof = ((RubyStringIO)str).eof;
            closedRead = ((RubyStringIO)str).closedRead;
            closedWrite = ((RubyStringIO)str).closedWrite;
            internal = ((RubyStringIO)str).internal;
            modes = ((RubyStringIO)str).modes;
        } else {
            eof = false;
            closedRead = false;
            closedWrite = false;
            internal = str.convertToString();
            String modeString = internal.isFrozen() ? "r" : "r+";
            try {
                modes = new ModeFlags(RubyIO.getIOModesIntFromString(getRuntime(), modeString));
            } catch (InvalidValueException e) {
                throw getRuntime().newErrnoEINVALError();
            }
        }

        if (args.length == 2) {
            Object modeArgument;
            if (args[1] instanceof RubyFixnum) {
                modeArgument = RubyFixnum.fix2long(args[1]);
            } else {
                modeArgument = args[1].convertToString().toString();
            }
            
            initializeModes(modeArgument);
        }
        
        if (internal.isFrozen() && modes.isWritable()) {
            throw getRuntime().newErrnoEACCESError("not opened for writing");
        }
        
        if (modes.isTruncate()) {
            // if doing explicit write mode, truncate incoming string
            internal.modifyCheck(); // prevent eventual COW
            internal.empty();
        }
        
        return this;
    }

    @JRubyMethod(name = "rewind")
    public IRubyObject rewind() {
        this.pos = 0L;
        this.eof = false;
        this.lineno = 0;
        return RubyFixnum.zero(getRuntime());
    }

    @JRubyMethod(name = "seek", required = 1, optional = 1)
    public IRubyObject seek(IRubyObject[] args) {
        long amount = RubyNumeric.num2long(args[0]);
        int whence = Stream.SEEK_SET;
        long newPosition = pos;

        if (args.length > 1 && !args[0].isNil()) whence = RubyNumeric.fix2int(args[1]);

        if (whence == Stream.SEEK_CUR) {
            newPosition += amount;
        } else if (whence == Stream.SEEK_END) {
            newPosition = internal.getByteList().length() + amount;
        } else {
            newPosition = amount;
        }

        if (newPosition < 0) throw getRuntime().newErrnoEINVALError();

        pos = newPosition;
        eof = false;
        
        return RubyFixnum.zero(getRuntime());
    }

    @JRubyMethod(name = "string=", required = 1)
    public IRubyObject set_string(IRubyObject arg) {
        reopen(new IRubyObject[] { arg });
        pos = 0;
        lineno = 0;
        return this;
    }

    @JRubyMethod(name = "sync=", required = 1)
    public IRubyObject set_sync(IRubyObject args) {
        return args;
    }
    
    @JRubyMethod(name = "size")
    public IRubyObject size() {
        return getRuntime().newFixnum(internal.getByteList().length());
    }

    @JRubyMethod(name = "string")
    public IRubyObject string() {
        return internal;
    }

    @JRubyMethod(name = "sync")
    public IRubyObject sync() {
        return getRuntime().getTrue();
    }
    
    @JRubyMethod(name = "sysread", optional = 2)
    public IRubyObject sysread(IRubyObject[] args) {
        IRubyObject obj = read(args);
        
        if (obj.isNil() || ((RubyString) obj).getByteList().length() == 0) throw getRuntime().newEOFError();
        
        return obj; 
    }

    @JRubyMethod(name = "truncate", required = 1)
    public IRubyObject truncate(IRubyObject arg) {
        checkWritable();

        int len = RubyFixnum.fix2int(arg);
        if (len < 0) {
            throw getRuntime().newErrnoEINVALError("negative legnth");
        }

        internal.modify();
        internal.getByteList().length(len);
        return getRuntime().newFixnum(len);
    }

    @JRubyMethod(name = "ungetc", required = 1)
    public IRubyObject ungetc(IRubyObject arg) {
        checkReadable();
        
        int c = RubyNumeric.num2int(arg);
        if (pos == 0) return getRuntime().getNil();
        internal.modify();
        pos--;
        internal.getByteList().set((int) pos, c);
        
        return getRuntime().getNil();
    }

    @JRubyMethod(name = {"write", "syswrite"}, required = 1)
    public IRubyObject write(ThreadContext context, IRubyObject arg) {
        checkWritable();
        String obj = arg.toString();
        append(context, arg);
        return getRuntime().newFixnum(obj.length());
    }

    /* rb: check_modifiable */
    @Override
    protected void checkFrozen() {
        if (internal.isFrozen()) throw getRuntime().newIOError("not modifiable string");
    }
    
    /* rb: readable */
    private void checkReadable() {
        checkInitialized();
        if (closedRead || !modes.isReadable()) {
            throw getRuntime().newIOError("not opened for reading");
        }
    }


    /* rb: writable */
    private void checkWritable() {
        checkInitialized();
        if (closedWrite || !modes.isWritable()) {
            throw getRuntime().newIOError("not opened for writing");
        }

        // Tainting here if we ever want it. (secure 4)
    }

    private void checkInitialized() {
        if (modes == null) {
            throw getRuntime().newIOError("uninitialized stream");
        }
    }

    private void setupModes() {
        closedWrite = false;
        closedRead = false;
        
        if (modes.isReadOnly()) closedWrite = true;
        if (!modes.isReadable()) closedRead = true;
    }
}
