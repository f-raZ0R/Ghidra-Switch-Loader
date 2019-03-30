/**
 * Copyright 2019 Adubbz
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package adubbz.switchloader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import adubbz.switchloader.kip1.KIP1Header;
import adubbz.switchloader.kip1.KIP1ProgramBuilder;
import adubbz.switchloader.nso0.NSO0Header;
import adubbz.switchloader.nso0.NSO0ProgramBuilder;
import adubbz.switchloader.nro0.NRO0Header;
import adubbz.switchloader.nro0.NRO0ProgramBuilder;
import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.bin.ByteProviderWrapper;
import ghidra.app.util.importer.MemoryConflictHandler;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.BinaryLoader;
import ghidra.app.util.opinion.LoadSpec;
import ghidra.app.util.opinion.LoaderTier;
import ghidra.framework.model.DomainFolder;
import ghidra.framework.store.LockException;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.address.AddressOverflowException;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.CompilerSpec;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

public class SwitchLoader extends BinaryLoader 
{
    public static final String SWITCH_NAME = "Nintendo Switch Binary";
    private BinaryType binaryType;

    @Override
    public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException 
    {
        List<LoadSpec> loadSpecs = new ArrayList<>();
        BinaryReader reader = new BinaryReader(provider, true);
        String magic_0x0 = reader.readAsciiString(0, 4);
        String magic_0x10 = reader.readAsciiString(0x10, 4);
        
        reader.setPointerIndex(0);

        if (magic_0x0.equals("KIP1")) 
        {
            this.binaryType = BinaryType.KIP1;
        }
        else if (magic_0x0.equals("NSO0"))
        {
            this.binaryType = BinaryType.NSO0;
        }
        else if (magic_0x10.equals("NRO0"))
        {
            this.binaryType = BinaryType.NRO0;
        }
        else if (magic_0x10.equals("KIP1"))
        {
            // Note: This is kinda a bad way of determining this, but for now it gets the job done
            // and I don't believe there are any clashes.
            this.binaryType = BinaryType.SX_KIP1; 
        }
        else
            return loadSpecs;

        loadSpecs.add(new LoadSpec(this, 0, new LanguageCompilerSpecPair("AARCH64:LE:64:v8A", "default"), true));

        return loadSpecs;
    }

    @Override
    protected List<Program> loadProgram(ByteProvider provider, String programName,
            DomainFolder programFolder, LoadSpec loadSpec, List<Option> options, MessageLog log,
            Object consumer, TaskMonitor monitor)
                    throws IOException, CancelledException 
    {
        LanguageCompilerSpecPair pair = loadSpec.getLanguageCompilerSpec();
        Language importerLanguage = getLanguageService().getLanguage(pair.languageID);
        CompilerSpec importerCompilerSpec = importerLanguage.getCompilerSpecByID(pair.compilerSpecID);

        Address baseAddr = importerLanguage.getAddressFactory().getDefaultAddressSpace().getAddress(0);
        Program prog = createProgram(provider, programName, baseAddr, getName(), importerLanguage, importerCompilerSpec, consumer);
        boolean success = false;

        try 
        {
            success = this.loadInto(provider, loadSpec, options, log, prog, monitor, MemoryConflictHandler.ALWAYS_OVERWRITE);
        }
        finally 
        {
            if (!success) 
            {
                prog.release(consumer);
                prog = null;
            }
        }

        List<Program> results = new ArrayList<Program>();
        if (prog != null) results.add(prog);
        return results;
    }

    @Override
    protected boolean loadProgramInto(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
            MessageLog messageLog, Program program, TaskMonitor monitor, MemoryConflictHandler memoryConflictHandler) 
                    throws IOException
    {
        BinaryReader reader;

        if (this.binaryType == BinaryType.SX_KIP1)
        {
            ByteProvider offsetProvider = new ByteProviderWrapper(provider, 0x10, provider.length() - 0x10);
            reader = new BinaryReader(offsetProvider, true);
            
            KIP1Header header = new KIP1Header(reader, 0x0);
            KIP1ProgramBuilder.loadKIP1(header, offsetProvider, program, memoryConflictHandler, monitor);
        }
        else
        {
            reader = new BinaryReader(provider, true);
            
            if (this.binaryType == BinaryType.KIP1)
            {
                KIP1Header header = new KIP1Header(reader, 0x0);
                KIP1ProgramBuilder.loadKIP1(header, provider, program, memoryConflictHandler, monitor);
            }

            else if (this.binaryType == BinaryType.NSO0)
            {
                NSO0Header header = new NSO0Header(reader, 0x0);
                NSO0ProgramBuilder.loadNSO0(header, provider, program, memoryConflictHandler, monitor);
            }
            else if (this.binaryType == BinaryType.NRO0)
            {
                NRO0Header header = new NRO0Header(reader, 0x0);
                NRO0ProgramBuilder.loadNRO0(header, provider, program, memoryConflictHandler, monitor);
            }
        }
        
        return true;
    }

    @Override
    public LoaderTier getTier() 
    {
        return LoaderTier.SPECIALIZED_TARGET_LOADER;
    }

    @Override
    public int getTierPriority() 
    {
        return 0;
    }

    @Override
    public String getName() 
    {
        return SWITCH_NAME;
    }

    private static enum BinaryType
    {
        KIP1("Kernel Initial Process"), 
        NSO0("Nintendo Shared Object"), 
        NRO0("Nintendo Relocatable Object"),
        SX_KIP1("Gateway Kernel Initial Process");
        
        public final String name;
        
        private BinaryType(String name)
        {
            this.name = name;
        }
    }
}
