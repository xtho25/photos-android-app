"""Builds the TinyCLIP model files for the AI edition of Photo Chooser.

Model: wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M (MIT licence, Microsoft TinyCLIP).

Outputs
  models/assets/clip/image_encoder.onnx   int8, input pixel_values [1,3,224,224] -> image_embeds [1,D]
  models/assets/clip/text_encoder.onnx    int8, input input_ids [1,77] int64     -> text_embeds  [1,D]
      (git-ignored, bundled into the AI APK by Gradle)
  app/src/ai/assets/clip/vocab.txt        one BPE token per line, line number = token id
  app/src/ai/assets/clip/merges.txt       BPE merges, highest priority first
  app/src/ai/assets/clip/tinyclip.json    preprocessing constants and calibrated match thresholds
  app/src/testAi/resources/clip_reference.tsv   tokenizer + text-embedding reference for JVM tests

Embeddings are L2-normalised inside the ONNX graphs, so a dot product is the cosine similarity.

Run from the repo root:
  ~/.cache/photo-chooser-tools/venv/bin/python -I tools/convert_tinyclip.py
"""

import json
import pathlib
import shutil
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import CLIPModel, CLIPProcessor, CLIPTokenizer

MODEL_ID = "wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M"
CONTEXT = 77
PROMPT = "a photo of {}."
OPSET = 17
IR_VERSION = 9  # conservative, readable by ONNX Runtime 1.31 on Android

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL_OUT = ROOT / "models" / "assets" / "clip"
ASSET_OUT = ROOT / "app" / "src" / "ai" / "assets" / "clip"
TEST_OUT = ROOT / "app" / "src" / "testAi" / "resources"
WORK = ROOT / "models" / "work"


def features(out):
    """transformers 5 returns an output object whose pooler_output is the projected embedding."""
    return out if torch.is_tensor(out) else out.pooler_output


class ImageEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        return F.normalize(features(self.model.get_image_features(pixel_values=pixel_values)), dim=-1)


class TextEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids):
        return F.normalize(features(self.model.get_text_features(input_ids=input_ids)), dim=-1)


def export(module, example, input_name, output_name, path):
    torch.onnx.export(
        module, (example,), str(path),
        input_names=[input_name], output_names=[output_name],
        opset_version=OPSET, dynamo=False, do_constant_folding=True,
    )
    m = onnx.load(str(path))
    m.ir_version = min(m.ir_version, IR_VERSION)
    onnx.save(m, str(path))


def quantize(src, dst, text=False):
    """Dynamic int8 quantisation. For the text encoder, quantising the MLP layers ruins accuracy
    (cosine to fp32 ~0.86), so only the token-embedding table (Gather) and attention MatMuls are
    quantised there (cosine ~0.998, 178 MB -> ~83 MB)."""
    kwargs = {}
    if text:
        names = [n.name for n in onnx.load(str(src)).graph.node if n.op_type == "MatMul"]
        kwargs = dict(
            op_types_to_quantize=["Gather", "MatMul"],
            nodes_to_exclude=[n for n in names if "self_attn" not in n],
        )
    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8, **kwargs)
    m = onnx.load(str(dst))
    m.ir_version = min(m.ir_version, IR_VERSION)
    onnx.save(m, str(dst))


def sample_images():
    """Public-domain sample photos shipped with scikit-image, for verification and calibration."""
    from skimage import data
    from PIL import Image
    return {
        "astronaut": Image.fromarray(data.astronaut()),
        "cup of coffee": Image.fromarray(data.coffee()),
        "cat": Image.fromarray(data.chelsea()),
        "rocket": Image.fromarray(data.rocket()),
    }


def main():
    for d in (MODEL_OUT, ASSET_OUT, TEST_OUT, WORK):
        d.mkdir(parents=True, exist_ok=True)

    print(f"loading {MODEL_ID}")
    model = CLIPModel.from_pretrained(MODEL_ID).eval()
    processor = CLIPProcessor.from_pretrained(MODEL_ID)
    tokenizer = CLIPTokenizer.from_pretrained(MODEL_ID)
    mean = [float(x) for x in processor.image_processor.image_mean]
    std = [float(x) for x in processor.image_processor.image_std]
    size = int(processor.image_processor.crop_size["height"])

    img_enc, txt_enc = ImageEncoder(model).eval(), TextEncoder(model).eval()

    # ---- export + quantise ----
    print("exporting ONNX")
    with torch.no_grad():
        export(img_enc, torch.zeros(1, 3, size, size), "pixel_values", "image_embeds", WORK / "image_fp32.onnx")
        export(txt_enc, torch.zeros(1, CONTEXT, dtype=torch.long), "input_ids", "text_embeds", WORK / "text_fp32.onnx")
    print("quantising to int8")
    quantize(WORK / "image_fp32.onnx", MODEL_OUT / "image_encoder.onnx")
    quantize(WORK / "text_fp32.onnx", MODEL_OUT / "text_encoder.onnx", text=True)

    # ---- tokenizer files (vocab in id order, merges in priority order) ----
    vocab = tokenizer.get_vocab()
    by_id = sorted(vocab.items(), key=lambda kv: kv[1])
    assert [i for _, i in by_id] == list(range(len(by_id))), "vocab ids must be contiguous"
    assert all("\n" not in t and " " not in t for t, _ in by_id)
    (ASSET_OUT / "vocab.txt").write_text("\n".join(t for t, _ in by_id) + "\n", encoding="utf-8")
    bpe = json.loads(tokenizer.backend_tokenizer.to_str())["model"]
    assert bpe["type"] == "BPE" and bpe.get("end_of_word_suffix") == "</w>", bpe.get("end_of_word_suffix")
    merges = [m if isinstance(m, str) else " ".join(m) for m in bpe["merges"]]
    (ASSET_OUT / "merges.txt").write_text("\n".join(merges) + "\n", encoding="utf-8")

    def ids_for(text):
        return tokenizer(text, padding="max_length", max_length=CONTEXT, truncation=True)["input_ids"]

    # ---- verify int8 ONNX against PyTorch ----
    img_sess = ort.InferenceSession(str(MODEL_OUT / "image_encoder.onnx"))
    txt_sess = ort.InferenceSession(str(MODEL_OUT / "text_encoder.onnx"))
    images = sample_images()
    labels = list(images)
    extra = ["beach", "dog", "passport", "birthday cake", "car", "snow"]
    prompts = [PROMPT.format(x) for x in labels + extra]

    with torch.no_grad():
        px = processor(images=list(images.values()), return_tensors="pt")["pixel_values"]
        img_t = img_enc(px).numpy()
        ids = torch.tensor([ids_for(p) for p in prompts])
        txt_t = txt_enc(ids).numpy()
    img_o = np.concatenate([img_sess.run(None, {"pixel_values": px[i:i + 1].numpy()})[0] for i in range(len(labels))])
    txt_o = np.concatenate([txt_sess.run(None, {"input_ids": ids[i:i + 1].numpy()})[0] for i in range(len(prompts))])
    img_cos = (img_t * img_o).sum(1)
    txt_cos = (txt_t * txt_o).sum(1)
    print(f"int8 vs fp32 cosine: images min {img_cos.min():.4f}, texts min {txt_cos.min():.4f}")
    if img_cos.min() < 0.97 or txt_cos.min() < 0.99:
        sys.exit("int8 model drifted too far from fp32")

    # ---- calibrate thresholds on int8 scores ----
    scores = img_o @ txt_o.T  # [images, prompts]
    print("scores (rows=images, cols=prompts):")
    print("            " + " ".join(f"{p[11:22]:>11}" for p in prompts))
    for i, lab in enumerate(labels):
        print(f"{lab:>11} " + " ".join(f"{s:11.3f}" for s in scores[i]))
    matched = np.array([scores[i, i] for i in range(len(labels))])
    unmatched = np.array([scores[i, j] for i in range(len(labels)) for j in range(len(prompts)) if j != i])
    min_score = float((matched.min() + np.percentile(unmatched, 95)) / 2)
    print(f"matched min {matched.min():.3f}, unmatched p95 {np.percentile(unmatched, 95):.3f} -> min_score {min_score:.3f}")

    meta = {
        "model": MODEL_ID,
        "licence": "MIT",
        "image_size": size,
        "mean": mean,
        "std": std,
        "context_length": CONTEXT,
        "start_token": tokenizer.bos_token_id,
        "end_token": tokenizer.eos_token_id,
        "pad_token": tokenizer.pad_token_id,
        "prompt": PROMPT,
        "embed_dim": int(img_o.shape[1]),
        "min_score": round(min_score, 4),
        "margin_from_best": 0.06,
        "max_results": 300,
    }
    (ASSET_OUT / "tinyclip.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")

    # ---- references for the JVM tests ----
    cases = prompts + ["A Photo of  Café au lait!", "it's my dog's 2nd birthday", ""]
    with (TEST_OUT / "clip_reference.tsv").open("w", encoding="utf-8") as f:
        for p in cases:
            pid = ids_for(p)
            emb = txt_sess.run(None, {"input_ids": np.array([pid], dtype=np.int64)})[0][0]
            f.write(p + "\t" + ",".join(map(str, pid)) + "\t" + ",".join(f"{x:.6f}" for x in emb) + "\n")

    shutil.rmtree(WORK)
    for p in sorted(MODEL_OUT.glob("*.onnx")):
        print(f"{p.relative_to(ROOT)}: {p.stat().st_size / 1e6:.1f} MB")
    print("done")


if __name__ == "__main__":
    main()
