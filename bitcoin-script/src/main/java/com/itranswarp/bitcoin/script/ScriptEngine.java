package com.itranswarp.bitcoin.script;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.itranswarp.bitcoin.io.BitcoinInput;
import com.itranswarp.bitcoin.script.op.OpVerify;
import com.itranswarp.bitcoin.struct.Transaction;
import com.itranswarp.bitcoin.struct.TxOut;
import com.itranswarp.bitcoin.util.BytesUtils;
import com.itranswarp.bitcoin.util.HashUtils;
import com.itranswarp.bitcoin.util.Secp256k1Utils;

/**
 * Bitcoin script engine to execute transaction script.
 * 
 * @author liaoxuefeng
 */
public class ScriptEngine {

	static final Log log = LogFactory.getLog(ScriptEngine.class);

	private final List<Op> ops;
	private String address = "";

	ScriptEngine(List<Op> ops) {
		this.ops = ops;
	}

	/**
	 * Try extract address from script.
	 * 
	 * @return Public key address or empty string "" if no address found.
	 */
	public String getExtractAddress() {
		return this.address;
	}

	/**
	 * Execute the script.
	 */
	public boolean execute(Transaction currentTx, int txInIndex, Map<String, TxOut> prevUtxos) {
		log.info("execute script...");
		ScriptContext context = new ScriptContextImpl(currentTx, txInIndex, prevUtxos);
		for (Op op : this.ops) {
			log.info("> " + op);
			if (!op.execute(context)) {
				log.warn("failed!");
				return false;
			}
			log.info("ok");
		}
		// check top of stack is non-zero:
		return OpVerify.executeVerify(context);
	}

	void printOp(Op op, Deque<byte[]> stack) {
		log.info("exec: " + op);
		stack.forEach((data) -> {
			log.info("  " + HashUtils.toHexString(data));
		});
	}

	/**
	 * Parse BitCoin script: https://en.bitcoin.it/wiki/Script
	 */
	public static ScriptEngine parse(byte[] sigScript, byte[] outScript) {
		int n = 0;
		List<Op> list = new ArrayList<>();
		String address = null;
		try (BitcoinInput input = new BitcoinInput(new ByteArrayInputStream(BytesUtils.concat(sigScript, outScript)))) {
			while ((n = input.read()) != (-1)) {
				if (n >= 0x01 && n <= 0x4b) {
					byte[] data = input.readBytes(n);
					Op op = new DataOp(n, data);
					list.add(op);
					log.info("OP: " + op);
					if (n == 20 && address == null) {
						// 20 bytes data treats as Hash160:
						address = Secp256k1Utils.hash160PublicKeyToAddress(data);
					} else if (n == 65 && address == null) {
						// 65 bytes uncompressed data:
						address = Secp256k1Utils.uncompressedPublicKeyToAddress(data);
					}
				} else {
					Op op = Ops.getOp(n);
					if (op == null) {
						throw new UnsupportedOperationException(String.format("Unsupported OP: 0x%02x", n));
					}
					list.add(op);
					log.info("OP: " + op);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		ScriptEngine engine = new ScriptEngine(list);
		engine.address = address == null ? "" : address;
		return engine;
	}

	@Override
	public String toString() {
		List<String> list = this.ops.stream().map((op) -> {
			return op.toString();
		}).collect(Collectors.toList());
		return "-- BEGIN ----\n" + String.join("\n", list) + "\n-- END ----";
	}
}

class ScriptContextImpl implements ScriptContext {

	private final Transaction transaction;
	private final int txInIndex;
	private final Map<String, TxOut> prevUtxos;
	private final Deque<byte[]> stack = new ArrayDeque<>();

	public ScriptContextImpl(Transaction transaction, int txInIndex, Map<String, TxOut> prevUtxos) {
		this.transaction = transaction;
		this.txInIndex = txInIndex;
		this.prevUtxos = prevUtxos;
	}

	@Override
	public void push(byte[] data) {
		stack.push(data);
	}

	@Override
	public byte[] pop() {
		return stack.pop();
	}

	@Override
	public Transaction getTransaction() {
		return this.transaction;
	}

	@Override
	public int getTxInIndex() {
		return this.txInIndex;
	}

	@Override
	public TxOut getUTXO(String txHash, long index) {
		String key = txHash + "#" + index;
		return this.prevUtxos.get(key);
	}
}
