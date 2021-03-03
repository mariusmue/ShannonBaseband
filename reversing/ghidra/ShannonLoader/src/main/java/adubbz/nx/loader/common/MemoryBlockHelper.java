/**
 * Copyright 2019 Adubbz
 * Modified by Grant Hernandez 2020
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package adubbz.nx.loader.common;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ghidra.app.util.MemoryBlockUtils;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.bin.format.FactoryBundledWithBinaryReader;
import ghidra.app.util.bin.format.elf.ElfHeader;
import ghidra.app.util.bin.format.elf.ElfSectionHeader;
import ghidra.app.util.bin.format.elf.ElfStringTable;
import ghidra.app.util.bin.format.elf.ElfSymbolTable;
import ghidra.framework.store.LockException;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.address.AddressOverflowException;
import ghidra.program.model.address.AddressRange;
import ghidra.program.model.address.AddressRangeImpl;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryBlockException;
import ghidra.program.model.mem.MemoryConflictException;
import ghidra.util.Msg;
import ghidra.util.exception.NotFoundException;
import ghidra.util.task.TaskMonitor;

public class MemoryBlockHelper 
{
    private TaskMonitor monitor;
    private Program program;
    private ByteProvider byteProvider;
    private MessageLog log;
    private long baseAddress;
    
    private List<String> sectionsToInit = new ArrayList<>();

    public MemoryBlockHelper(TaskMonitor monitor, Program program, MessageLog log, ByteProvider byteProvider, long baseAddress)
    {
        this.monitor = monitor;
        this.program = program;
        this.log = log;
        this.byteProvider = byteProvider;
        this.baseAddress = baseAddress;
    }

    public MemoryBlock addManualDeferredSection(String name, long addressOffset, InputStream dataInput, long dataSize, boolean read, boolean write, boolean execute)
    {
        AddressSpace addressSpace = this.program.getAddressFactory().getDefaultAddressSpace();
        MemoryBlock mb = null;
        
        try 
        {
            mb = MemoryBlockUtils.createUninitializedBlock(program, false, name, addressSpace.getAddress(this.baseAddress + addressOffset), dataSize, "", null, read, write, execute, this.log);
            
            if (mb == null)
            {
                Msg.error(this, "error");
            }
        } 
        catch (AddressOutOfBoundsException e) 
        {
            e.printStackTrace();
        }
        
        return mb;
    }

    public void addUninitializedBlock(String name, long addressOffset, long dataSize, boolean read, boolean write, boolean execute)
    {
        try {
          AddressSpace addressSpace = this.program.getAddressFactory().getDefaultAddressSpace();
          MemoryBlockUtils.createUninitializedBlock(program, false, name, addressSpace.getAddress(this.baseAddress + addressOffset), dataSize, "", null, read, write, execute, this.log);
        } catch (AddressOutOfBoundsException e) {
            e.printStackTrace();
        }
    }
    
    public void addDeferredSection(String name, long addressOffset, InputStream dataInput, long dataSize, boolean read, boolean write, boolean execute)
    {
        MemoryBlock mb = this.addManualDeferredSection(name, addressOffset, dataInput, dataSize, read, write, execute);
        
        if (mb != null)
        {
            this.sectionsToInit.add(mb.getName());
        }
    }
    
    public void addSection(String name, long addressOffset, InputStream dataInput, long dataSize, boolean read, boolean write, boolean execute) throws AddressOverflowException, AddressOutOfBoundsException
    {
        AddressSpace addressSpace = this.program.getAddressFactory().getDefaultAddressSpace();
        MemoryBlockUtils.createInitializedBlock(this.program, true, name, addressSpace.getAddress(this.baseAddress + addressOffset), dataInput, dataSize, "", null, read, write, execute, this.log, this.monitor);
    }

    public void addMergeSection(String name, long addressOffset, InputStream dataInput, long dataSize) throws AddressOverflowException, AddressOutOfBoundsException
    {
        AddressSpace addressSpace = this.program.getAddressFactory().getDefaultAddressSpace();
        Address writeStart = addressSpace.getAddress(this.baseAddress + addressOffset);

        Memory memory = this.program.getMemory();
        MemoryBlock target = memory.getBlock(writeStart);

        // no section to merge with, fallback to just adding a new block
        if (target == null) {
          addSection(name, addressOffset, dataInput, dataSize, true, true, true);
          return;
        }

        try {
          if (!target.isInitialized())
            memory.convertToInitialized(target, (byte)0);

          byte [] data = new byte[(int)dataSize];
          dataInput.read(data, 0, (int)dataSize);
          target.putBytes(writeStart, data);
          target.setName(name);
          Msg.info(this, String.format("Creating merge block %s -> [%08x - %08x]",
                name, addressOffset, addressOffset+dataSize));
        } catch (NotFoundException | LockException | MemoryAccessException | IOException e) {
          Msg.error(this, String.format("Creating merge block %s failed", name));
          e.printStackTrace();
        }
    }
    
    public void finalizeSection(String name) throws LockException, NotFoundException, MemoryAccessException, IOException
    {
        Memory memory = this.program.getMemory();
        Msg.info(this, "Attempting to manually finalize " + name);
        
        for (MemoryBlock block : memory.getBlocks())
        {
            if (block.getName().equals(name))
            {
                Msg.info(this, "Manually finalizing " + name);
                memory.convertToInitialized(block, (byte)0);
                byte[] data = this.byteProvider.readBytes(block.getStart().getOffset() - this.baseAddress, block.getSize());
                block.putBytes(block.getStart(), data);
            }
        }
    }
    
    public void finalizeSections() throws LockException, NotFoundException, MemoryAccessException, IOException
    {
        Memory memory = this.program.getMemory();
        
        for (MemoryBlock block : memory.getBlocks())
        {
            if (this.sectionsToInit.contains(block.getName()))
            {
                memory.convertToInitialized(block, (byte)0);
                byte[] data = this.byteProvider.readBytes(block.getStart().getOffset() - this.baseAddress, block.getSize());
                block.putBytes(block.getStart(), data);
            }
        }
    }
    
    private class DeferredInitSection
    {
        private MemoryBlock block;
        private InputStream dataInput;
        
        public DeferredInitSection(MemoryBlock block, InputStream dataInput)
        {
            this.block = block;
            this.dataInput = dataInput;
        }
    }
}