use turbovec::{f16_bits_to_f32, AddError, IdMapIndex, TurboQuantIndex};

const DIM: usize = 64;

fn int8_vectors(n: usize) -> Vec<i8> {
    (0..n * DIM)
        .map(|i| ((i as i32 * 37 + 11).rem_euclid(255) - 127) as i8)
        .collect()
}

fn f16_pattern_vectors(n: usize) -> Vec<u16> {
    const VALUES: &[u16] = &[
        0x3c00, // 1.0
        0xbc00, // -1.0
        0x3800, // 0.5
        0xb800, // -0.5
        0x4000, // 2.0
        0xc000, // -2.0
        0x3400, // 0.25
        0xb400, // -0.25
    ];
    (0..n * DIM).map(|i| VALUES[i % VALUES.len()]).collect()
}

#[test]
fn f16_bits_conversion_handles_common_values() {
    assert_eq!(f16_bits_to_f32(0x0000), 0.0);
    assert_eq!(f16_bits_to_f32(0x8000), -0.0);
    assert_eq!(f16_bits_to_f32(0x3c00), 1.0);
    assert_eq!(f16_bits_to_f32(0xc000), -2.0);
    assert_eq!(f16_bits_to_f32(0x7c00), f32::INFINITY);
    assert_eq!(f16_bits_to_f32(0xfc00), f32::NEG_INFINITY);
    assert!(f16_bits_to_f32(0x7e00).is_nan());
    assert_eq!(f16_bits_to_f32(0x0001), 2.0_f32.powi(-24));
}

#[test]
fn int8_add_and_search_match_equivalent_f32_inputs() {
    let vectors_i8 = int8_vectors(4);
    let queries_i8 = vectors_i8[..2 * DIM].to_vec();
    let vectors_f32: Vec<f32> = vectors_i8.iter().map(|&value| value as f32).collect();
    let queries_f32: Vec<f32> = queries_i8.iter().map(|&value| value as f32).collect();

    let mut int8_index = TurboQuantIndex::new(DIM, 4).unwrap();
    int8_index.add_i8(&vectors_i8);
    let int8_results = int8_index.search_i8(&queries_i8, 3);

    let mut f32_index = TurboQuantIndex::new(DIM, 4).unwrap();
    f32_index.add(&vectors_f32);
    let f32_results = f32_index.search(&queries_f32, 3);

    assert_eq!(int8_results.indices, f32_results.indices);
    assert_eq!(int8_results.scores, f32_results.scores);
}

#[test]
fn f16_add_and_search_match_equivalent_f32_inputs() {
    let vectors_f16 = f16_pattern_vectors(4);
    let queries_f16 = vectors_f16[..2 * DIM].to_vec();
    let vectors_f32: Vec<f32> = vectors_f16
        .iter()
        .map(|&bits| f16_bits_to_f32(bits))
        .collect();
    let queries_f32: Vec<f32> = queries_f16
        .iter()
        .map(|&bits| f16_bits_to_f32(bits))
        .collect();

    let mut f16_index = TurboQuantIndex::new(DIM, 4).unwrap();
    f16_index.add_f16_2d(&vectors_f16, DIM).unwrap();
    let f16_results = f16_index.search_f16(&queries_f16, 3);

    let mut f32_index = TurboQuantIndex::new(DIM, 4).unwrap();
    f32_index.add_2d(&vectors_f32, DIM).unwrap();
    let f32_results = f32_index.search(&queries_f32, 3);

    assert_eq!(f16_results.indices, f32_results.indices);
    assert_eq!(f16_results.scores, f32_results.scores);
}

#[test]
fn f16_add_rejects_nan_half_values() {
    let mut index = TurboQuantIndex::new(DIM, 4).unwrap();
    let mut vectors = vec![0x3c00; DIM];
    vectors[7] = 0x7e00;

    let err = index.add_f16_2d(&vectors, DIM).unwrap_err();
    match err {
        AddError::InvalidInputValue {
            vector_index: 0,
            coord_index: 7,
            value,
        } => assert!(value.is_nan()),
        other => panic!("expected InvalidInputValue for f16 NaN, got {other:?}"),
    }
    assert_eq!(index.len(), 0);
}

#[test]
fn id_map_int8_search_supports_allowlists() {
    let vectors = int8_vectors(4);
    let queries = vectors[..DIM].to_vec();
    let ids = [10, 20, 30, 40];

    let mut index = IdMapIndex::new(DIM, 4).unwrap();
    index.add_i8_with_ids(&vectors, &ids).unwrap();

    let (_scores, found) = index.search_i8_with_allowlist(&queries, 2, Some(&[20, 40]));
    assert_eq!(found.len(), 2);
    assert!(found.iter().all(|id| *id == 20 || *id == 40));
}
