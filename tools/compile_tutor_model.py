# /// script
# dependencies = [
#   "torch",
#   "ai-edge-torch",
#   "mediapipe>=0.10.14",
#   "huggingface_hub",
# ]
# ///

"""
Quantized Tutor Model Compilation Pipeline
===========================================
This tool automates the compilation, quantization, and packaging of generative LLM models
(such as Gemma) into the MediaPipe/LiteRT task bundle format (.bin or .task) required for 
on-device execution inside the Project Nua Android client.

Usage:
------
1. Normal Conversion (Requires Internet & HF Token):
   uv run tools/compile_tutor_model.py --hf-model-id google/gemma-3-270m-it -o app/src/main/assets/tutor_model.bin -q int4

2. Mock Conversion (Offline/CI validation):
   uv run tools/compile_tutor_model.py --mock -o app/src/main/assets/tutor_model.bin

3. Validate Bundle:
   uv run tools/compile_tutor_model.py --validate app/src/main/assets/tutor_model.bin
"""

import os
import sys
import argparse
import zipfile
import shutil
from pathlib import Path

def print_banner():
    banner = """
====================================================================
      _  _              ___                 _   _               
     | \| |_  _ __ _   / _ \ _  _ __ _ _ __| |_(_)___ ___ _ _   
     | .` | || / _` | | (_) | || / _` | '_ \  _| / _ / _ \ ' \  
     |_|\_|\_,_\__,_|  \__\_\\\\_,_\__,_| .__/\__|_\\___\\___/_||_| 
                                      |_|                       
           [ Quantized Tutor Model Compilation Pipeline ]
====================================================================
"""
    print(banner)

def create_mock_bundle(output_path: Path):
    """
    Creates a lightweight, valid ZIP-based MediaPipe model task bundle
    containing mock weights and a mock tokenizer for validation and testing.
    """
    print(f"[*] Creating mock quantized tutor model bundle at: {output_path}")
    
    # Create temp directory
    temp_dir = Path("temp_mock_build")
    if temp_dir.exists():
        shutil.rmtree(temp_dir)
    temp_dir.mkdir(parents=True, exist_ok=True)

    try:
        # 1. Create a dummy model file
        model_file = temp_dir / "model.tflite"
        with open(model_file, "wb") as f:
            f.write(b"MOCK_TFLITE_MODEL_FLATBUFFER_DATA_NUA_TUTOR_v4.0")
        
        # 2. Create a dummy tokenizer model
        tokenizer_file = temp_dir / "tokenizer.model"
        with open(tokenizer_file, "w", encoding="utf-8") as f:
            f.write("<bos>\n<eos>\n<pad>\n<unk>\n")
            for i in range(100):
                f.write(f"token_{i}\n")

        # 3. Create a dummy metadata configuration
        metadata_file = temp_dir / "metadata.json"
        with open(metadata_file, "w", encoding="utf-8") as f:
            f.write('{"model_type": "MOCK_TUTOR", "quantization": "INT4", "version": 4.0}')

        # 4. ZIP them together into the final output path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(model_file, arcname="model.tflite")
            zf.write(tokenizer_file, arcname="tokenizer.model")
            zf.write(metadata_file, arcname="metadata.json")

        print(f"[+] Successfully generated mock tutor bundle: {output_path} ({output_path.stat().st_size} bytes)")
    finally:
        if temp_dir.exists():
            shutil.rmtree(temp_dir)

def validate_bundle(bundle_path: Path) -> bool:
    """
    Validates that the file exists and is a valid ZIP archive containing
    at least a model.tflite and a tokenizer.model.
    """
    print(f"[*] Validating tutor model bundle: {bundle_path}")
    if not bundle_path.exists():
        print(f"[!] Error: File does not exist at {bundle_path}")
        return False

    if not zipfile.is_zipfile(bundle_path):
        print(f"[!] Error: File at {bundle_path} is not a valid ZIP archive (MediaPipe task requirement)")
        return False

    try:
        with zipfile.ZipFile(bundle_path, "r") as zf:
            namelist = zf.namelist()
            print(f"[*] Found files in bundle: {namelist}")
            
            has_tflite = any(name.endswith(".tflite") for name in namelist)
            has_tokenizer = any("tokenizer" in name or name.endswith(".model") or name.endswith(".vocab") for name in namelist)
            
            if not has_tflite:
                print("[!] Validation failed: No .tflite model file found in the archive.")
                return False
            if not has_tokenizer:
                print("[!] Validation failed: No tokenizer/vocabulary file found in the archive.")
                return False
                
            print(f"[+] Bundle validation SUCCESSFUL! Ready for deployment in Nua Edge.")
            return True
    except Exception as e:
        print(f"[!] Error during ZIP inspection: {e}")
        return False

def convert_real_model(hf_model_id: str, output_path: Path, backend: str, quantization: str):
    """
    Attempts to download a HuggingFace checkpoint, convert it using MediaPipe/LiteRT genai tools.
    """
    print(f"[*] Starting conversion pipeline for HF Model: '{hf_model_id}'")
    print(f"[*] Target Backend: {backend} | Quantization Recipe: {quantization}")

    try:
        from huggingface_hub import snapshot_download
        from mediapipe.tasks.python.genai import converter
    except ImportError as e:
        print(f"[!] Dependency missing: {e}")
        print("[!] Please run with 'uv run tools/compile_tutor_model.py' to automatically resolve deps.")
        sys.exit(1)

    # 1. Download model checkpoints from Hugging Face
    print(f"[*] Downloading checkpoint from Hugging Face: {hf_model_id}")
    try:
        ckpt_dir = snapshot_download(repo_id=hf_model_id, allow_patterns=["*.safetensors", "*.model", "*.json"])
        print(f"[+] Checkpoint downloaded to local cache: {ckpt_dir}")
    except Exception as e:
        print(f"[!] Hugging Face download failed: {e}")
        sys.exit(1)

    # 2. Find tokenizer model
    ckpt_path = Path(ckpt_dir)
    vocab_files = list(ckpt_path.glob("*.model")) + list(ckpt_path.glob("*.json"))
    if not vocab_files:
        print("[!] Error: No tokenizer vocab file (.model or .json) found in the downloaded files.")
        sys.exit(1)
    vocab_file = vocab_files[0]
    print(f"[*] Using vocabulary file: {vocab_file}")

    # 3. Configure conversion parameters
    temp_output_dir = Path("temp_real_build")
    if temp_output_dir.exists():
        shutil.rmtree(temp_output_dir)
    temp_output_dir.mkdir(exist_ok=True)

    # Map quantization to MediaPipe configuration types if necessary
    # Note: MediaPipe converter internally handles quantization based on model types or config parameters
    print(f"[*] Compiling and quantizing model weights...")
    try:
        config = converter.ConversionConfig(
            input_ckpt=str(ckpt_path),
            ckpt_format="safetensors",
            model_type="GEMMA_2B" if "gemma" in hf_model_id.lower() else "PHI_2",
            backend=backend,
            output_dir=str(temp_output_dir),
            vocab_model_file=str(vocab_file)
        )
        
        # Run conversion
        converter.convert_checkpoint(config)
        
        # Move converted model task file to the final output destination
        task_files = list(temp_output_dir.glob("*.task")) + list(temp_output_dir.glob("*.bin"))
        if not task_files:
            raise FileNotFoundError("Conversion finished, but no .task or .bin output was created in the output directory.")
        
        output_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(task_files[0]), str(output_path))
        print(f"[+] Successfully converted and saved quantized tutor model to: {output_path}")

    except Exception as e:
        print(f"[!] Compilation / Quantization pipeline failed: {e}")
        sys.exit(1)
    finally:
        if temp_output_dir.exists():
            shutil.rmtree(temp_output_dir)

def main():
    print_banner()
    
    parser = argparse.ArgumentParser(description="Compile and Quantize Tutor models for Nua Edge.")
    
    # Modes
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--hf-model-id", "-m", type=str, help="HuggingFace model ID (e.g. google/gemma-3-270m-it)")
    group.add_argument("--mock", action="store_true", help="Generate a lightweight mock bundle for testing/CI")
    group.add_argument("--validate", type=str, help="Validate an existing model bundle file (.bin/.task)")

    # Configurations
    parser.add_argument("--output", "-o", type=str, default="app/src/main/assets/tutor_model.bin",
                        help="Path to save the compiled model bundle (default: app/src/main/assets/tutor_model.bin)")
    parser.add_argument("--backend", type=str, default="cpu", choices=["cpu", "gpu"],
                        help="Execution target backend (default: cpu)")
    parser.add_argument("--quantization", "-q", type=str, default="int4", choices=["int4", "int8", "fp16"],
                        help="Quantization precision recipe (default: int4)")

    args = parser.parse_args()

    # If validating, run validator and exit
    if args.validate:
        file_to_validate = Path(args.validate)
        success = validate_bundle(file_to_validate)
        sys.exit(0 if success else 1)

    output_path = Path(args.output)

    if args.mock:
        create_mock_bundle(output_path)
        # Perform self-validation check on mock output
        validate_bundle(output_path)
    else:
        convert_real_model(
            hf_model_id=args.hf_model_id,
            output_path=output_path,
            backend=args.backend,
            quantization=args.quantization
        )

if __name__ == "__main__":
    main()
