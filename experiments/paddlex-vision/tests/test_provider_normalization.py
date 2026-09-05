from paddlex_vision_experiment.provider import (
    VisionAnalysis,
    normalize_object_payload,
    normalize_ocr_payload,
)
from paddlex_vision_experiment.ocr_bridge import PaddleXOcrBridge


def test_normalizes_paddlex_object_payload():
    objects = normalize_object_payload(
        {
            "boxes": [
                {"label": "cat", "score": 0.96, "coordinate": [1, 2, 30, 40]},
                {"label": "", "score": 0.4, "coordinate": [1, 2, 3, 4]},
                {"label": "bad", "score": 0.4, "coordinate": [1, 2, 3]},
            ]
        }
    )

    assert objects[0].label == "cat"
    assert objects[0].bbox == (1.0, 2.0, 30.0, 40.0)


def test_normalizes_chinese_ocr_and_provides_ocr_compatible_text():
    texts = normalize_ocr_payload(
        {
            "rec_texts": ["猫", "粮"],
            "rec_scores": [0.9, 0.8],
            "rec_boxes": [[1, 2, 20, 30], [22, 2, 40, 30]],
        }
    )
    result = VisionAnalysis(
        input_path="fixture.png",
        objects=(),
        texts=texts,
        relations=(),
    )

    assert result.ocr_text == "猫粮"
    assert result.as_dict()["texts"][0]["text"] == "猫"


def test_ocr_bridge_matches_text_only_consumer_contract():
    class FakeProvider:
        def analyze(self, image):
            return VisionAnalysis(
                input_path=str(image),
                objects=(),
                texts=normalize_ocr_payload(
                    {
                        "rec_texts": ["你好", "世界"],
                        "rec_boxes": [[0, 0, 10, 10], [11, 0, 20, 10]],
                    }
                ),
                relations=(),
            )

    assert PaddleXOcrBridge(FakeProvider()).do_ocr("fixture.png") == "你好世界"
