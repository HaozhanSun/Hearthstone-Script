from paddlex_vision_experiment.association import associate_objects_and_text


def test_associates_nearest_text_to_the_right_of_object():
    relations = associate_objects_and_text(
        objects=[(100, 200, 350, 500)],
        texts=[(700, 200, 900, 250), (370, 250, 600, 300)],
    )

    assert [relation.as_dict() for relation in relations] == [
        {
            "object_index": 0,
            "text_index": 1,
            "relation": "right_of",
            "distance": 260.0,
        }
    ]


def test_associates_text_below_object_when_horizontally_overlapping():
    relations = associate_objects_and_text(
        objects=[(100, 100, 300, 250)],
        texts=[(120, 280, 280, 320)],
    )

    assert len(relations) == 1
    assert relations[0].relation == "below"


def test_does_not_associate_far_diagonal_text():
    assert associate_objects_and_text(
        objects=[(0, 0, 100, 100)],
        texts=[(500, 500, 650, 550)],
    ) == []
