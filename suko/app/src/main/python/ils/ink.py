"""Shared rule for what counts as 'ink' (the dark line art) in an element."""
import numpy as np

INK_MAX = 115  # a pixel is ink when its brightest channel is below this (black lines yes, mid-blue / red fills no)


def ink_mask(crop, m):
    """crop: BGR crop, m: bool mask of the element inside the crop -> bool mask of the dark line art."""
    return (crop.max(axis=2) < INK_MAX) & m
